package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedNullability
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbol
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypeKind

/**
 * The nullable flow analysis of C# nullable reference types (task 0.1.80 of CSHARP_PSI_MIGRATION.md, the rest of D2): per function, the
 * null state of every local, parameter and member access path (`x`, `this.F`, `x.A.B`) through assignments, null tests (`== null`, `is null`,
 * `is not null`, `is T t`, `is { }`), `??`, `??=`, `?.`, `!`, `&&` / `||` / `!`, early `return` / `throw`, loops (to a fixpoint), `try`,
 * `switch`, and the attributes of `System.Diagnostics.CodeAnalysis` of the source and of the index of assemblies (`[NotNullWhen]`,
 * `[MaybeNullWhen]`, `[NotNull]`, `[MaybeNull]`, `[AllowNull]`, `[DisallowNull]`, `[NotNullIfNotNull]`, `[DoesNotReturn]`,
 * `[DoesNotReturnIf]`, `[MemberNotNull]`, `[MemberNotNullWhen]`). Warnings, with Roslyn's codes and spans: CS8602 (dereference of a maybe-null
 * value), CS8600 / CS8601 / CS8625 (a maybe-null value or `null` to a non-nullable local, parameter, field or property), CS8604 (to a
 * non-nullable parameter), CS8603 (returned where a non-nullable reference is), CS8618 (a constructor leaves a non-nullable field or
 * auto-property null).
 *
 * Precision first: a state is [N.MAYBE] only where Roslyn's surely is maybe-null. Anything the analysis does not understand makes the
 * states it may touch [N.UNKNOWN] — a call it cannot resolve (it may be `[DoesNotReturn]`, take `[NotNull]` arguments), a type parameter,
 * an oblivious declaration (no nullable context), a pattern other than the plain null and type tests, outer variables inside lambdas and
 * local functions (analyzed apart, as Roslyn does not carry the states into them reliably), `goto`. The states are ordered
 * `NOT < UNKNOWN < MAYBE` and the join of two is the greater, which keeps that promise: maybe-null on some path is maybe-null.
 */
internal class CSharpNullableFlow(
    private val r: CSharpNameResolver,
    /** Whether the analysis keeps out of [element] (a member with a syntax error, an incomplete member…). */
    private val quiet: (PsiElement) -> Boolean,
    /** Partial types are complete (the source generators ran and are fresh): CS8618 may look at all their parts. */
    private val generatorsKnown: Boolean,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val file: CSharpFile = r.file

    /** The null state of a value. */
    enum class N { NOT, UNKNOWN, MAYBE }

    private fun max(a: N, b: N): N = if (a.ordinal >= b.ordinal) a else b

    // ---- slots and states

    private object This
    private object Static

    /** A member on a path: its identity ([key]) and what its declaration says ([declared], asked when the path has no state yet). */
    private class Step(val key: Any, val name: String?, declared: () -> N) {
        val declared: N by lazy(LazyThreadSafetyMode.NONE, declared)
        override fun equals(other: Any?): Boolean = other is Step && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }

    /** A variable or a member access path: a local or parameter ([LocalSymbol]), `this` or a static member, then members. */
    private data class Slot(val root: Any, val steps: List<Step>) {
        fun child(step: Step): Slot = Slot(root, steps + step)
        fun isUnder(other: Slot): Boolean = root == other.root && steps.size > other.steps.size && steps.subList(0, other.steps.size) == other.steps
    }

    /** [unknownDefaults]: what has no state of its own is [N.UNKNOWN], not what it is declared (the code may have been unreachable). */
    private class State(val values: HashMap<Slot, N> = HashMap(), var unknownDefaults: Boolean = false, var unreachable: Boolean = false) {
        fun copy(): State = State(HashMap(values), unknownDefaults, unreachable)
    }

    private fun bottom(): State = State(unreachable = true)

    private fun read(s: State, slot: Slot): N {
        if (s.unreachable) return N.NOT
        s.values[slot]?.let { return it }
        if (s.unknownDefaults) return N.UNKNOWN
        return declared(slot)
    }

    private fun write(s: State, slot: Slot, value: N) {
        if (s.unreachable) return
        if (s.values.keys.any { it.isUnder(slot) }) s.values.keys.removeIf { it.isUnder(slot) }
        s.values[slot] = value
    }

    /** `x = y`: what is known of the members of `y` is known of the members of `x` (as Roslyn copies the nested slots). */
    private fun copyNested(s: State, from: Slot?, to: Slot) {
        if (from == null || from == to || s.unreachable) return
        val nested = s.values.entries.filter { it.key.isUnder(from) }.map { it.key to it.value }
        for ((slot, value) in nested) {
            val steps = to.steps + slot.steps.drop(from.steps.size)
            if (steps.size <= MAX_DEPTH) s.values[Slot(to.root, steps)] = value
        }
    }

    private fun set(s: State, slot: Slot, value: N) {
        if (!s.unreachable) s.values[slot] = value
    }

    private fun join(states: List<State>): State {
        val reachable = states.filter { !it.unreachable }
        if (reachable.isEmpty()) return bottom()
        if (reachable.size == 1) return reachable.single().copy()
        val result = State(unknownDefaults = reachable.any { it.unknownDefaults })
        val keys = reachable.flatMapTo(HashSet()) { it.values.keys }
        for (key in keys) result.values[key] = reachable.map { read(it, key) }.reduce(::max)
        return result
    }

    private fun join(a: State, b: State): State = join(listOf(a, b))

    private fun assign(target: State, from: State) {
        if (target === from) return
        target.values.clear()
        target.values.putAll(from.values)
        target.unknownDefaults = from.unknownDefaults
        target.unreachable = from.unreachable
    }

    private fun same(a: State, b: State): Boolean {
        if (a.unreachable || b.unreachable) return a.unreachable == b.unreachable
        if (a.unknownDefaults != b.unknownDefaults) return false
        return (a.values.keys + b.values.keys).all { read(a, it) == read(b, it) }
    }

    /** The code that follows may never run (a call that may not return): nothing read there is surely maybe-null. */
    private fun havoc(s: State) {
        if (s.unreachable) return
        for (entry in s.values.entries) if (entry.value == N.MAYBE) entry.setValue(N.UNKNOWN)
        s.unknownDefaults = true
    }

    // ---- functions

    private class Target(val isLoop: Boolean) {
        val breaks = ArrayList<State>()
        val continues = ArrayList<State>()
    }

    /** A function being analyzed: its element, whether it is inside another one, and whether its returns must not be null. */
    private inner class Fn(val owner: PsiElement, val nested: Boolean, val returnsNotNull: Boolean) {
        val exits = ArrayList<State>()
        val targets = ArrayList<Target>()
        val bindings = ArrayList<Slot?>()
    }

    private lateinit var fn: Fn
    private var silent = 0

    private fun warn(code: String, message: String, at: PsiElement) {
        if (silent > 0) return
        if (!CSharpWarningContext.nullableAt(file, at.textRange.startOffset).warnings) return
        report(code, message, at.textRange)
    }

    /** Every function of the file: methods, constructors, accessors, operators, local functions, lambdas, the top-level statements. */
    fun run(unit: CSharpCompilationUnit) {
        if (!mayWarn()) return
        val globals = ArrayList<CSharpStatement>()
        PsiTreeUtil.processElements(unit) { element ->
            ProgressManager.checkCanceled()
            when (element) {
                is CSharpGlobalStatement -> element.statement?.let(globals::add)
                is CSharpConstructorDeclaration -> analyze(element) { constructor(element, it) }
                is CSharpMethodDeclaration -> analyze(element, returnsNotNull(element.returnType, element.modifiers, element, element.attributeLists)) { body(element.body, element.expressionBody, it) }
                is CSharpOperatorDeclaration -> analyze(element, returnsNotNull(element.returnType, element.modifiers, element, element.attributeLists)) { body(element.body, element.expressionBody, it) }
                is CSharpConversionOperatorDeclaration -> analyze(element, returnsNotNull(element.type, element.modifiers, element, element.attributeLists)) { body(element.body, element.expressionBody, it) }
                is CSharpDestructorDeclaration -> analyze(element) { body(element.body, element.expressionBody, it) }
                is CSharpAccessorDeclaration -> {
                    val property = element.parent?.parent as? CSharpBasePropertyDeclaration
                    val getter = element.keyword?.text == "get" && property != null && !hasNullableAttributes(property.attributeLists)
                    val returns = getter && returnsNotNull(property?.type, property?.modifiers.orEmpty(), element, element.attributeLists)
                    analyze(element, returns) { body(element.body, element.expressionBody, it) }
                }
                is CSharpPropertyDeclaration -> element.expressionBody?.let { arrow ->
                    analyze(element, !hasNullableAttributes(element.attributeLists) && returnsNotNull(element.type, element.modifiers, element, emptyList())) { body(null, arrow, it) }
                }
                is CSharpIndexerDeclaration -> element.expressionBody?.let { arrow ->
                    analyze(element, !hasNullableAttributes(element.attributeLists) && returnsNotNull(element.type, element.modifiers, element, emptyList())) { body(null, arrow, it) }
                }
                is CSharpLocalFunctionStatement -> analyze(element, returnsNotNull(element.returnType, element.modifiers, element, element.attributeLists), nested = true) {
                    body(element.body, element.expressionBody, it)
                }
                is CSharpAnonymousFunctionExpression -> analyze(element, false, nested = true) { s ->
                    element.block?.let { statement(it, s) } ?: element.expressionBody?.let { eval(it, s) }
                }
            }
            true
        }
        if (globals.isNotEmpty() && !quiet(globals.first())) analyze(unit) { s -> globals.forEach { statement(it, s) } }
    }

    private fun mayWarn(): Boolean {
        if (CSharpWarningContext.nullableAt(file, file.textLength).warnings || CSharpWarningContext.nullableAt(file, 0).warnings) return true
        return file.textContains('#') && file.text.contains("nullable")
    }

    private fun analyze(owner: PsiElement, returnsNotNull: Boolean = false, nested: Boolean = false, walk: (State) -> Unit) {
        if (quiet(owner)) return
        // labels and `goto` make the order of the statements no order of execution
        if (PsiTreeUtil.findChildOfAnyType(owner, CSharpGotoStatement::class.java, CSharpLabeledStatement::class.java) != null) return
        val outer = if (this::fn.isInitialized) fn else null
        fn = Fn(owner, nested, returnsNotNull)
        try {
            walk(State())
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: StackOverflowError) {
            // a pathological nesting: no warnings rather than none at all
        } finally {
            if (outer != null) fn = outer
        }
    }

    private fun body(block: CSharpBlock?, arrow: CSharpArrowExpressionClause?, s: State) {
        if (block != null) {
            statement(block, s)
        } else if (arrow != null) {
            val expression = arrow.expression ?: return
            val value = eval(expression, s)
            if (fn.returnsNotNull) checkReturn(expression, value, s)
        }
        fn.exits += s.copy()
    }

    /** Whether the declared return type [type] of [function] is a non-nullable reference type the returns are checked against. */
    private fun returnsNotNull(type: CSharpType?, modifiers: List<PsiElement>, function: PsiElement, attributeLists: List<CSharpAttributeList>): Boolean {
        type ?: return false
        if (attributeLists.any { it.target?.identifier?.text == "return" }) return false
        // iterators return what `yield` gives
        if (PsiTreeUtil.findChildOfType(function, CSharpYieldStatement::class.java) != null) return false
        var returned: CSharpType = type
        if (modifiers.any { it.text == "async" }) {
            val generic = type as? CSharpGenericName ?: (type as? CSharpQualifiedName)?.right as? CSharpGenericName ?: return false
            if (generic.identifier?.text != "Task" && generic.identifier?.text != "ValueTask") return false
            returned = generic.typeArgumentList?.arguments?.singleOrNull() ?: return false
        }
        return typeNullability(returned, r) == N.NOT && isReference(r.resolveType(returned))
    }

    private fun checkReturn(expression: CSharpExpression, value: N, s: State) {
        if (value == N.MAYBE && !s.unreachable) warn("CS8603", "Possible null reference return.", expression)
    }

    // ---- constructors: CS8618

    private class Required(val key: Any, val name: String, val kind: String)

    private fun constructor(ctor: CSharpConstructorDeclaration, s: State) {
        val static = ctor.modifiers.any { it.text == "static" }
        val chained = ctor.initializer?.thisOrBaseKeyword?.text == "this"
        val type = ctor.parent as? CSharpTypeDeclaration
        val required = if (type == null || chained) emptyList() else uninitialized(type, static, setsRequired = hasAttribute(ctor.attributeLists, "SetsRequiredMembers"))
        val root: Any = if (static) Static else This
        for (member in required) s.values[Slot(root, listOf(Step(member.key, member.name) { N.MAYBE }))] = N.MAYBE
        ctor.initializer?.argumentList?.arguments?.let { unknownArguments(it, s) }
        body(ctor.body, ctor.expressionBody, s)
        if (required.isEmpty()) return
        val exit = join(fn.exits)
        if (exit.unreachable) return
        val name = ctor.identifier ?: return
        for (member in required) {
            if (read(exit, Slot(root, listOf(Step(member.key, member.name) { N.MAYBE }))) != N.MAYBE) continue
            warn("CS8618", "Non-nullable ${member.kind} '${member.name}' must contain a non-null value when exiting constructor. " +
                "Consider adding the 'required' modifier or declaring the ${member.kind} as nullable.", name)
        }
    }

    /**
     * The fields and auto-properties of [type] a constructor must set: of a non-nullable reference type, without an initializer, not
     * `required` (unless the constructor is `[SetsRequiredMembers]`), no nullable attribute; of every part of a partial type when they are
     * all known, none otherwise. Only classes and records without a parameter list.
     */
    private fun uninitialized(type: CSharpTypeDeclaration, static: Boolean, setsRequired: Boolean): List<Required> {
        if (type.elementType != SyntaxKind.ClassDeclaration && type.elementType != SyntaxKind.RecordDeclaration) return emptyList()
        val parts: List<CSharpTypeDeclaration> = if (type.modifiers.any { it.text == "partial" }) {
            if (!generatorsKnown) return emptyList()
            val info = r.syntax.declaredType(type) ?: return emptyList()
            info.parts.map { it.element() as? CSharpTypeDeclaration ?: return emptyList() }
        } else listOf(type)
        if (parts.any { it.parameterList != null }) return emptyList()
        val result = ArrayList<Required>()
        for (part in parts) {
            val resolver = (part.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return emptyList()
            for (member in part.members) {
                val modifiers = member.modifiers.map { it.text }
                if (("static" in modifiers) != static) continue
                if ("const" in modifiers || "abstract" in modifiers || "extern" in modifiers || ("required" in modifiers && !setsRequired)) continue
                if (hasNullableAttributes(member.attributeLists)) continue
                when (member) {
                    is CSharpBaseFieldDeclaration -> {
                        if ("fixed" in modifiers) continue
                        val declaration = member.declaration ?: continue
                        if (typeNullability(declaration.type, resolver) != N.NOT || !isReference(declaration.type?.let(resolver::resolveType))) continue
                        for (variable in declaration.variables) {
                            if (variable.initializer != null || variable.argumentList != null) continue
                            val name = variable.identifier?.text ?: continue
                            result += Required(variable, name, if (member is CSharpEventFieldDeclaration) "event" else "field")
                        }
                    }
                    is CSharpPropertyDeclaration -> {
                        if (member.initializer != null || member.expressionBody != null || member.explicitInterfaceSpecifier != null) continue
                        val accessors = member.accessorList?.accessors ?: continue
                        if (accessors.isEmpty() || accessors.any { it.body != null || it.expressionBody != null }) continue
                        if (typeNullability(member.type, resolver) != N.NOT || !isReference(member.type?.let(resolver::resolveType))) continue
                        val name = member.identifier?.text ?: continue
                        result += Required(member, name, "property")
                    }
                }
            }
        }
        return result
    }

    // ---- statements

    private fun statement(st: CSharpStatement?, s: State) {
        st ?: return
        if (s.unreachable) return
        ProgressManager.checkCanceled()
        when (st) {
            is CSharpBlock -> for (child in st.statements) {
                if (s.unreachable) break
                statement(child, s)
            }
            is CSharpLocalFunctionStatement, is CSharpEmptyStatement -> {}
            is CSharpLocalDeclarationStatement -> st.declaration?.let { declaration(it, s) }
            is CSharpExpressionStatement -> eval(st.expression, s)
            is CSharpIfStatement -> {
                val (t, f) = condition(st.condition, s)
                statement(st.statement, t)
                st.`else`?.statement?.let { statement(it, f) }
                assign(s, join(t, f))
            }
            is CSharpWhileStatement -> loop(s) { head ->
                val (t, f) = condition(st.condition, head)
                statement(st.statement, t)
                t to f
            }
            is CSharpDoStatement -> loop(s) { head ->
                statement(st.statement, head)
                // `continue` goes to the condition
                val target = fn.targets.last()
                val atCondition = join(listOf(head) + target.continues)
                target.continues.clear()
                condition(st.condition, atCondition)
            }
            is CSharpForStatement -> {
                st.declaration?.let { declaration(it, s) }
                st.initializers.forEach { eval(it, s) }
                loop(s, after = { back -> st.incrementors.forEach { eval(it, back) } }) { head ->
                    val (t, f) = if (st.condition == null) head to bottom() else condition(st.condition, head)
                    statement(st.statement, t)
                    t to f
                }
            }
            is CSharpCommonForEachStatement -> {
                val collection = st.expression
                val value = eval(collection, s)
                if (collection != null && st.awaitKeyword == null) dereference(collection, value, s)
                loop(s) { head ->
                    val t = head.copy()
                    when (st) {
                        is CSharpForEachStatement -> st.identifier?.let(r.syntax::symbolAt)?.let { set(t, Slot(it, emptyList()), N.UNKNOWN) }
                        is CSharpForEachVariableStatement -> st.variable?.let { declareUnknown(it, t) }
                    }
                    statement(st.statement, t)
                    t to head.copy()
                }
            }
            is CSharpReturnStatement -> {
                val expression = st.expression
                if (expression != null) {
                    val value = eval(expression, s)
                    if (fn.returnsNotNull) checkReturn(expression, value, s)
                }
                if (!s.unreachable) fn.exits += s.copy()
                s.unreachable = true
            }
            is CSharpThrowStatement -> {
                eval(st.expression, s)
                s.unreachable = true
            }
            is CSharpYieldStatement -> if (st.returnOrBreakKeyword?.text == "break") {
                fn.exits += s.copy()
                s.unreachable = true
            } else eval(st.expression, s)
            is CSharpBreakStatement -> {
                fn.targets.lastOrNull()?.breaks?.add(s.copy())
                s.unreachable = true
            }
            is CSharpContinueStatement -> {
                fn.targets.lastOrNull { it.isLoop }?.continues?.add(s.copy())
                s.unreachable = true
            }
            is CSharpTryStatement -> tryStatement(st, s)
            is CSharpSwitchStatement -> switchStatement(st, s)
            is CSharpUsingStatement -> {
                st.declaration?.let { declaration(it, s) }
                st.expression?.let { eval(it, s) }
                statement(st.statement, s)
            }
            is CSharpLockStatement -> {
                eval(st.expression, s)
                statement(st.statement, s)
            }
            is CSharpFixedStatement -> {
                st.declaration?.let { declaration(it, s) }
                statement(st.statement, s)
            }
            is CSharpCheckedStatement -> statement(st.block, s)
            is CSharpUnsafeStatement -> statement(st.block, s)
            else -> havoc(s)
        }
    }

    /**
     * A loop: [iteration] runs the condition and the body on a state of the head and gives the state that goes on (true) and the one that
     * leaves (false). Without reporting until the head no longer changes, then once more with warnings. [after]: what runs on the way back
     * (the incrementors of `for`).
     */
    private fun loop(s: State, after: (State) -> Unit = {}, iteration: (State) -> Pair<State, State>) {
        val entry = s.copy()
        fun pass(head: State): Pair<State, State> {
            val target = Target(isLoop = true)
            fn.targets += target
            try {
                val (onward, leaving) = iteration(head.copy())
                val back = join(listOf(onward) + target.continues)
                after(back)
                return back to join(listOf(leaving) + target.breaks)
            } finally {
                fn.targets.removeAt(fn.targets.size - 1)
            }
        }
        var head = entry
        silent++
        try {
            var stable = false
            for (i in 0 until 6) {
                val next = join(entry, pass(head).first)
                if (same(next, head)) {
                    stable = true
                    break
                }
                head = next
            }
            if (!stable) head = join(entry, head).also(::havoc)
        } finally {
            silent--
        }
        assign(s, pass(head).second)
    }

    private fun tryStatement(st: CSharpTryStatement, s: State) {
        val start = s.copy()
        val inTry = s.copy()
        statement(st.block, inTry)
        // a catch or a finally may start anywhere in the try block: what it writes is anything
        val catchStart = join(start, inTry)
        for (slot in written(st.block)) set(catchStart, slot, max(read(catchStart, slot), N.UNKNOWN))
        val ends = arrayListOf(inTry)
        for (clause in st.catches) {
            var c = catchStart.copy()
            clause.declaration?.identifier?.let(r.syntax::symbolAt)?.let { set(c, Slot(it, emptyList()), N.NOT) }
            clause.filter?.filterExpression?.let { c = condition(it, c).first }
            statement(clause.block, c)
            ends += c
        }
        val normal = join(ends)
        val finally = st.finally?.block
        if (finally == null) {
            assign(s, normal)
            return
        }
        val anywhere = join(normal, catchStart)
        statement(finally, anywhere)
        val goingOn = normal.copy()
        silent++
        try {
            statement(finally, goingOn)
        } finally {
            silent--
        }
        if (anywhere.unreachable) goingOn.unreachable = true
        assign(s, goingOn)
    }

    /** The slots [element] writes: assigned, incremented, passed by `ref` or `out` (lambdas left out). */
    private fun written(element: PsiElement?): List<Slot> {
        element ?: return emptyList()
        val found = ArrayList<Slot>()
        PsiTreeUtil.processElements(element) { e ->
            when (e) {
                is CSharpAssignmentExpression -> slotOf(e.left)?.let(found::add)
                is CSharpArgument -> if (e.refKindKeyword != null) slotOf(e.expression)?.let(found::add)
                is CSharpPrefixUnaryExpression -> slotOf(e.operand)?.let(found::add)
                is CSharpPostfixUnaryExpression -> slotOf(e.operand)?.let(found::add)
            }
            true
        }
        return found
    }

    private fun switchStatement(st: CSharpSwitchStatement, s: State) {
        val input = eval(st.expression, s)
        val base = s.copy()
        slotOf(st.expression)?.let { set(base, it, N.UNKNOWN) }
        val target = Target(isLoop = false)
        fn.targets += target
        var exhaustive = false
        val ends = ArrayList<State>()
        try {
            for (section in st.sections) {
                var c = base.copy()
                for (label in section.labels) {
                    when (label) {
                        is CSharpDefaultSwitchLabel -> exhaustive = true
                        is CSharpCasePatternSwitchLabel -> {
                            label.pattern?.let { declarePattern(it, c, input) }
                            if (section.labels.size == 1) label.whenClause?.condition?.let { c = condition(it, c).first }
                            if (label.pattern is CSharpDiscardPattern && label.whenClause == null) exhaustive = true
                        }
                        is CSharpCaseSwitchLabel -> eval(label.value, c)
                    }
                }
                for (child in section.statements) {
                    if (c.unreachable) break
                    statement(child, c)
                }
                ends += c
            }
        } finally {
            fn.targets.removeAt(fn.targets.size - 1)
        }
        assign(s, join(target.breaks + ends + if (exhaustive) emptyList() else listOf(base)))
    }

    private fun declaration(declaration: CSharpVariableDeclaration, s: State) {
        val type = declaration.type
        val explicit = type != null && !r.isVar(type) && type !is CSharpRefType
        val notNull = explicit && typeNullability(type, r) == N.NOT && isReference(type?.let(r::resolveType))
        for (variable in declaration.variables) {
            val local = variable.identifier?.let(r.syntax::symbolAt) ?: continue
            val initializer = variable.initializer?.value
            val value = when {
                initializer == null -> N.UNKNOWN
                isFunction(initializer) -> N.NOT
                else -> eval(initializer, s)
            }
            if (initializer != null && notNull && value == N.MAYBE && !s.unreachable) warn("CS8600", CS8600, initializer)
            val target = Slot(local, emptyList())
            write(s, target, if (type is CSharpRefType) N.UNKNOWN else value)
            if (initializer != null && type !is CSharpRefType) copyNested(s, slotOf(initializer), target)
        }
    }

    // ---- expressions

    private fun isFunction(e: CSharpExpression): Boolean = strip(e) is CSharpAnonymousFunctionExpression

    private fun strip(e: CSharpExpression): CSharpExpression {
        var at = e
        while (at is CSharpParenthesizedExpression) at = at.expression ?: return at
        return at
    }

    /** The null state of the value of [e], with the effects of evaluating it on [s]. */
    private fun eval(e: CSharpExpression?, s: State): N {
        e ?: return N.UNKNOWN
        if (s.unreachable) return N.NOT
        ProgressManager.checkCanceled()
        return when (e) {
            is CSharpParenthesizedExpression -> eval(e.expression, s)
            is CSharpLiteralExpression -> if (e.elementType == SyntaxKind.NullLiteralExpression || e.elementType == SyntaxKind.DefaultLiteralExpression) N.MAYBE else N.NOT
            is CSharpInterpolatedStringExpression -> {
                for (part in e.contents) (part as? CSharpInterpolation)?.expression?.let { eval(it, s) }
                N.NOT
            }
            is CSharpThisExpression, is CSharpBaseExpression -> N.NOT
            is CSharpIdentifierName -> name(e, s)
            is CSharpType -> N.NOT
            is CSharpSimpleName -> N.NOT
            is CSharpMemberAccessExpression -> memberAccess(e, s)
            is CSharpConditionalAccessExpression -> conditionalAccess(e, s)
            is CSharpMemberBindingExpression -> binding(e, s)
            is CSharpElementBindingExpression -> {
                e.argumentList?.arguments?.forEach { eval(it.expression, s) }
                N.UNKNOWN
            }
            is CSharpInvocationExpression -> invocation(e, s, null)
            is CSharpElementAccessExpression -> {
                val receiver = e.expression
                val value = eval(receiver, s)
                if (receiver != null && !isStaticReceiver(receiver)) dereference(receiver, value, s)
                e.argumentList?.arguments?.forEach { eval(it.expression, s) }
                val type = receiver?.let(r::typeOf)
                if (type is SemanticType.Library && type.fullName == "System.String") N.NOT else N.UNKNOWN
            }
            is CSharpAssignmentExpression -> assignment(e, s)
            is CSharpBinaryExpression -> binary(e, s)
            is CSharpConditionalExpression -> {
                val (t, f) = condition(e.condition, s)
                val whenTrue = if (e.whenTrue?.let(::isFunction) == true) N.NOT else eval(e.whenTrue, t)
                val whenFalse = if (e.whenFalse?.let(::isFunction) == true) N.NOT else eval(e.whenFalse, f)
                val value = when {
                    t.unreachable && f.unreachable -> N.NOT
                    t.unreachable -> whenFalse
                    f.unreachable -> whenTrue
                    else -> max(whenTrue, whenFalse)
                }
                assign(s, join(t, f))
                value
            }
            is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text == "!") valueOfCondition(e, s) else {
                eval(e.operand, s)
                N.NOT
            }
            is CSharpPostfixUnaryExpression -> {
                eval(e.operand, s)
                // `x!`: whether Roslyn takes `x` for not null after it is not known
                if (e.operatorToken?.text == "!") slotOf(e.operand)?.let { if (read(s, it) == N.MAYBE) set(s, it, N.UNKNOWN) }
                N.NOT
            }
            is CSharpCastExpression -> cast(e, s)
            is CSharpIsPatternExpression -> valueOfCondition(e, s)
            is CSharpBaseObjectCreationExpression -> {
                e.argumentList?.arguments?.let { unknownArguments(it, s) }
                e.initializer?.let { initializer(it, s, (e as? CSharpObjectCreationExpression)?.type?.let(r::resolveType) ?: r.typeOf(e)) }
                N.NOT
            }
            is CSharpArrayCreationExpression -> {
                e.initializer?.expressions?.forEach { eval(it, s) }
                N.NOT
            }
            is CSharpImplicitArrayCreationExpression -> {
                e.initializer?.expressions?.forEach { eval(it, s) }
                N.NOT
            }
            is CSharpCollectionExpression -> {
                for (element in e.elements) when (element) {
                    is CSharpExpressionElement -> eval(element.expression, s)
                    is CSharpSpreadElement -> eval(element.expression, s)
                }
                N.NOT
            }
            is CSharpAnonymousObjectCreationExpression -> {
                e.initializers.forEach { eval(it.expression, s) }
                N.NOT
            }
            is CSharpAnonymousFunctionExpression -> N.NOT
            is CSharpTupleExpression -> {
                e.arguments.forEach { eval(it.expression, s) }
                N.NOT
            }
            is CSharpThrowExpression -> {
                eval(e.expression, s)
                s.unreachable = true
                N.NOT
            }
            is CSharpAwaitExpression -> {
                eval(e.expression, s)
                N.UNKNOWN
            }
            is CSharpSwitchExpression -> switchExpression(e, s)
            is CSharpTypeOfExpression, is CSharpSizeOfExpression -> N.NOT
            is CSharpDefaultExpression -> {
                val type = e.type?.let(r::resolveType)
                if (isReference(type)) N.MAYBE else if (type != null && type !is SemanticType.Parameter) N.NOT else N.UNKNOWN
            }
            is CSharpCheckedExpression -> eval(e.expression, s)
            is CSharpWithExpression -> {
                eval(e.expression, s)
                e.initializer?.expressions?.forEach { eval(it, s) }
                N.NOT
            }
            is CSharpRangeExpression -> {
                eval(e.leftOperand, s)
                eval(e.rightOperand, s)
                N.NOT
            }
            is CSharpQueryExpression -> N.UNKNOWN
            is CSharpDeclarationExpression -> {
                declareUnknown(e, s)
                N.UNKNOWN
            }
            is CSharpRefExpression -> {
                eval(e.expression, s)
                slotOf(e.expression)?.let { set(s, it, N.UNKNOWN) }
                N.UNKNOWN
            }
            is CSharpStackAllocArrayCreationExpression, is CSharpImplicitStackAllocArrayCreationExpression -> N.NOT
            else -> {
                havoc(s)
                N.UNKNOWN
            }
        }
    }

    private fun valueOfCondition(e: CSharpExpression, s: State): N {
        val (t, f) = condition(e, s)
        assign(s, join(t, f))
        return N.NOT
    }

    /** The locals [e] declares (`var (a, b)`, `out var x`) and what it assigns in a deconstruction: of no known state. */
    private fun declareUnknown(e: CSharpExpression, s: State) {
        PsiTreeUtil.processElements(e) { element ->
            when (element) {
                is CSharpSingleVariableDesignation -> element.identifier?.let(r.syntax::symbolAt)?.let { write(s, Slot(it, emptyList()), N.UNKNOWN) }
                is CSharpIdentifierName -> slotOf(element)?.let { write(s, it, N.UNKNOWN) }
                is CSharpMemberAccessExpression -> slotOf(element)?.let { write(s, it, N.UNKNOWN) }
            }
            true
        }
    }

    private fun name(e: CSharpIdentifierName, s: State): N {
        val symbol = symbolOf(e) ?: return N.UNKNOWN
        return when (symbol) {
            is CSharpSymbol.Local -> when (symbol.symbol.kind) {
                LocalSymbolKind.LOCAL, LocalSymbolKind.PARAMETER -> slotOf(e)?.let { read(s, it) } ?: N.UNKNOWN
                LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> N.UNKNOWN
                else -> N.NOT
            }
            is CSharpSymbol.SourceMember, is CSharpSymbol.LibraryMember ->
                if (isValueMember(symbol)) slotOf(e)?.let { read(s, it) } ?: memberDeclared(symbol, null, e) else N.NOT
            else -> N.NOT
        }
    }

    private fun memberAccess(e: CSharpMemberAccessExpression, s: State): N {
        val receiver = e.expression ?: return N.UNKNOWN
        val symbol = e.nameElement?.let(::symbolOf)
        if (isStaticReceiver(receiver)) {
            return if (symbol != null && isValueMember(symbol)) slotOf(e)?.let { read(s, it) } ?: memberDeclared(symbol, null, e) else if (symbol == null) N.UNKNOWN else N.NOT
        }
        val value = eval(receiver, s)
        if (symbol == null) return N.UNKNOWN
        if (!isValueMember(symbol)) return N.NOT
        if (isStatic(symbol) != true) dereference(receiver, value, s)
        return slotOf(e)?.let { read(s, it) } ?: if (isPath(receiver)) N.UNKNOWN else memberDeclared(symbol, receiver, e)
    }

    /**
     * A variable or a member path the analysis does not follow (a variable captured from the outer function, a path deeper than
     * [MAX_DEPTH]): it may have been tested, its members are not what they are declared.
     */
    private fun isPath(e: CSharpExpression): Boolean = when (val x = strip(e)) {
        is CSharpIdentifierName -> (symbolOf(x) as? CSharpSymbol.Local)?.symbol?.kind.let { it == LocalSymbolKind.LOCAL || it == LocalSymbolKind.PARAMETER || it == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER }
        is CSharpMemberAccessExpression -> x.expression?.let { isPath(it) || it is CSharpThisExpression } == true
        is CSharpPostfixUnaryExpression -> x.operand?.let(::isPath) == true
        else -> false
    }

    private fun conditionalAccess(e: CSharpConditionalAccessExpression, s: State): N {
        val receiver = e.expression ?: return N.UNKNOWN
        val value = eval(receiver, s)
        if (s.unreachable) return N.NOT
        val slot = slotOf(receiver)
        val inner = s.copy()
        slot?.let { set(inner, it, N.NOT) }
        fn.bindings += slot
        val rest = try {
            eval(e.whenNotNull, inner)
        } finally {
            fn.bindings.removeAt(fn.bindings.size - 1)
        }
        assign(s, join(s, inner))
        return when {
            value == N.MAYBE || rest == N.MAYBE -> N.MAYBE
            else -> N.UNKNOWN
        }
    }

    private fun binding(e: CSharpMemberBindingExpression, s: State): N {
        val symbol = e.nameElement?.let(::symbolOf) ?: return N.UNKNOWN
        if (!isValueMember(symbol)) return N.NOT
        return slotOf(e)?.let { read(s, it) } ?: if (fn.bindings.lastOrNull() == null && fn.bindings.isNotEmpty()) memberDeclared(symbol, null, e) else N.UNKNOWN
    }

    /** `x.F`, `x[i]`, `x.M()`, `foreach (… in x)`: CS8602 when [receiver] may be null; after it, it is not (as Roslyn, one warning a value). */
    private fun dereference(receiver: CSharpExpression, value: N, s: State) {
        if (s.unreachable) return
        if (value == N.MAYBE && isReference(r.typeOf(receiver))) warn("CS8602", "Dereference of a possibly null reference.", receiver)
        slotOf(receiver)?.let { set(s, it, N.NOT) }
    }

    private fun cast(e: CSharpCastExpression, s: State): N {
        val value = eval(e.expression, s)
        val type = e.type ?: return N.UNKNOWN
        val resolved = r.resolveType(type)
        if (!isReference(resolved)) return if (resolved != null && resolved !is SemanticType.Parameter && r.isValueType(resolved)) N.NOT else N.UNKNOWN
        if (value == N.MAYBE && typeNullability(type, r) == N.NOT) {
            if (!s.unreachable) warn("CS8600", CS8600, e)
            return N.UNKNOWN
        }
        return value
    }

    private fun binary(e: CSharpBinaryExpression, s: State): N = when (e.operatorToken?.text) {
        "??" -> coalesce(e, s)
        "&&", "||", "==", "!=", "is" -> valueOfCondition(e, s)
        // `x as T` is null when x is or the conversion fails; Roslyn knows the conversion (an upcast of a not-null value is not null)
        "as" -> if (eval(e.left, s) == N.MAYBE) N.MAYBE else N.UNKNOWN
        else -> {
            eval(e.left, s)
            eval(e.right, s)
            N.NOT
        }
    }

    private fun coalesce(e: CSharpBinaryExpression, s: State): N {
        val left = e.left ?: return N.UNKNOWN
        val leftValue = eval(left, s)
        if (s.unreachable) return N.NOT
        val notNull = s.copy()
        learnNotNull(left, notNull)
        val whenNull = s.copy()
        slotOf(left)?.let { if (leftValue != N.MAYBE) set(whenNull, it, N.UNKNOWN) }
        val right = e.right
        val rightValue = if (right != null && isFunction(right)) N.NOT else eval(right, whenNull)
        assign(s, join(notNull, whenNull))
        return when {
            whenNull.unreachable -> N.NOT
            leftValue == N.NOT && rightValue == N.MAYBE -> N.UNKNOWN
            else -> rightValue
        }
    }

    private fun switchExpression(e: CSharpSwitchExpression, s: State): N {
        val input = eval(e.governingExpression, s)
        if (s.unreachable) return N.NOT
        val base = s.copy()
        slotOf(e.governingExpression)?.let { set(base, it, N.UNKNOWN) }
        val ends = ArrayList<State>()
        var value: N? = null
        for (arm in e.arms) {
            var c = base.copy()
            arm.pattern?.let { declarePattern(it, c, input) }
            arm.whenClause?.condition?.let { c = condition(it, c).first }
            val armValue = arm.expression?.let { if (isFunction(it)) N.NOT else eval(it, c) } ?: N.UNKNOWN
            if (!c.unreachable) value = value?.let { max(it, armValue) } ?: armValue
            ends += c
        }
        if (ends.isEmpty()) {
            havoc(s)
            return N.UNKNOWN
        }
        assign(s, join(ends))
        return value ?: N.NOT
    }

    // ---- assignments

    private fun assignment(e: CSharpAssignmentExpression, s: State): N {
        val left = e.left ?: return N.UNKNOWN
        val right = e.right
        when (e.operatorToken?.text) {
            "=" -> {
                if (left is CSharpDeclarationExpression || left is CSharpTupleExpression) {
                    eval(right, s)
                    declareUnknown(left, s)
                    return N.UNKNOWN
                }
                val slot = slotOf(left)
                targetReceiver(left, s)
                val value = if (right == null) N.UNKNOWN else if (isFunction(right)) N.NOT else eval(right, s)
                if (right != null && value == N.MAYBE && !s.unreachable) checkTarget(left, right)
                if (slot != null) write(s, slot, value) else forget(left, s)
                if (slot != null && right != null) copyNested(s, slotOf(right), slot)
                return value
            }
            "??=" -> {
                val slot = slotOf(left)
                val leftValue = eval(left, s)
                if (s.unreachable) return N.NOT
                val notNull = s.copy()
                slot?.let { set(notNull, it, N.NOT) }
                val whenNull = s.copy()
                val value = if (right == null) N.UNKNOWN else if (isFunction(right)) N.NOT else eval(right, whenNull)
                if (right != null && value == N.MAYBE && !whenNull.unreachable) checkTarget(left, right)
                if (slot != null) write(whenNull, slot, if (leftValue == N.NOT && value == N.MAYBE) N.UNKNOWN else value) else forget(left, whenNull)
                assign(s, join(notNull, whenNull))
                return if (leftValue == N.NOT && value == N.MAYBE) N.UNKNOWN else value
            }
            else -> {
                eval(left, s)
                eval(right, s)
                val slot = slotOf(left)
                val type = r.typeOf(left)
                val result = if (type is SemanticType.Library && type.fullName == "System.String") N.NOT else N.UNKNOWN
                if (slot != null) write(s, slot, result) else forget(left, s)
                return result
            }
        }
    }

    /** The receiver of the target of an assignment is evaluated (and dereferenced) before the value. */
    private fun targetReceiver(left: CSharpExpression, s: State) {
        when (val target = strip(left)) {
            is CSharpMemberAccessExpression -> {
                val receiver = target.expression ?: return
                if (isStaticReceiver(receiver)) return
                val value = eval(receiver, s)
                val symbol = target.nameElement?.let(::symbolOf)
                if (symbol != null && isStatic(symbol) != true) dereference(receiver, value, s)
            }
            is CSharpElementAccessExpression -> {
                val receiver = target.expression ?: return
                val value = eval(receiver, s)
                dereference(receiver, value, s)
                target.argumentList?.arguments?.forEach { eval(it.expression, s) }
            }
            else -> {}
        }
    }

    /**
     * A maybe-null [value] goes to [target]: CS8600 for a local or a parameter by value (Roslyn's "legacy" warnings), else CS8625 for a
     * `null` constant and CS8601 for anything else — when the target is surely of a non-nullable reference type.
     */
    private fun checkTarget(target: CSharpExpression, value: CSharpExpression) {
        val name = when (val t = strip(target)) {
            is CSharpIdentifierName -> t
            is CSharpMemberAccessExpression -> t.nameElement
            else -> null
        } ?: return
        when (val symbol = symbolOf(name)) {
            is CSharpSymbol.Local -> {
                val declaration = symbol.symbol.declaration
                when (symbol.symbol.kind) {
                    LocalSymbolKind.LOCAL -> {
                        val declarator = declaration.parent as? CSharpVariableDeclarator ?: return
                        val variables = declarator.parent as? CSharpVariableDeclaration ?: return
                        val type = variables.type ?: return
                        if (r.isVar(type) || type is CSharpRefType) return
                        if (typeNullability(type, r) == N.NOT && isReference(r.resolveType(type))) warn("CS8600", CS8600, value)
                    }
                    LocalSymbolKind.PARAMETER -> {
                        val parameter = declaration.parent as? CSharpParameter ?: return
                        if (hasNullableAttributes(parameter.attributeLists)) return
                        val modifiers = parameter.modifiers.map { it.text }
                        if ("params" in modifiers || "this" in modifiers || "in" in modifiers || "readonly" in modifiers) return
                        if (typeNullability(parameter.type, r) != N.NOT || !isReference(parameter.type?.let(r::resolveType))) return
                        if ("ref" in modifiers || "out" in modifiers) warn(if (isNullConstant(value)) "CS8625" else "CS8601", if (isNullConstant(value)) CS8625 else CS8601, value)
                        else warn("CS8600", CS8600, value)
                    }
                    else -> {}
                }
            }
            is CSharpSymbol.SourceMember, is CSharpSymbol.LibraryMember -> {
                if (!isValueMember(symbol) || memberAccepts(symbol) != N.NOT) return
                warn(if (isNullConstant(value)) "CS8625" else "CS8601", if (isNullConstant(value)) CS8625 else CS8601, value)
            }
            else -> {}
        }
    }

    /** An object initializer: the values of `{ Name = value }` go to the members of [created]. */
    private fun initializer(initializer: CSharpInitializerExpression, s: State, created: SemanticType?) {
        for (item in initializer.expressions) {
            when (item) {
                is CSharpAssignmentExpression -> {
                    val right = item.right
                    if (right is CSharpInitializerExpression) {
                        initializer(right, s, null)
                        continue
                    }
                    (item.left as? CSharpImplicitElementAccess)?.argumentList?.arguments?.forEach { eval(it.expression, s) }
                    val value = if (right == null || isFunction(right)) N.NOT else eval(right, s)
                    if (right != null && value == N.MAYBE && created != null && !s.unreachable) {
                        val name = item.left as? CSharpIdentifierName
                        val symbol = name?.let(::symbolOf)
                        if (symbol != null && isValueMember(symbol) && memberAccepts(symbol) == N.NOT) {
                            warn(if (isNullConstant(right)) "CS8625" else "CS8601", if (isNullConstant(right)) CS8625 else CS8601, right)
                        }
                    }
                }
                is CSharpInitializerExpression -> initializer(item, s, null)
                else -> eval(item, s)
            }
        }
    }

    // ---- calls

    private class Param(
        val name: String, val refKind: String?, val isParams: Boolean,
        /** What it takes: [N.NOT] a non-nullable reference (CS8604 for a maybe-null argument), [N.MAYBE] null too, [N.UNKNOWN] not known. */
        val accepts: N,
        /** The state of an `out` / `ref` argument after the call. */
        val after: N,
        val annotations: IndexedNullability.Annotations,
        /** Its type is a type parameter: `[MaybeNullWhen]` and the like say what they say only for reference types. */
        val generic: Boolean,
    )

    private class Call(
        val parameters: List<Param>, val doesNotReturn: Boolean, val memberNotNull: List<String>, val memberNotNullWhen: Map<Boolean, List<String>>,
        val display: String,
    ) {
        /** Something that changes the states after the call beyond its `out` arguments. */
        val hasEffects: Boolean get() = doesNotReturn || memberNotNull.isNotEmpty() || memberNotNullWhen.isNotEmpty() ||
            parameters.any { it.annotations.notNull || it.annotations.notNullWhen != null || it.annotations.doesNotReturnIf != null || it.annotations.maybeNullWhen != null }
    }

    private class Branches(var whenTrue: State? = null, var whenFalse: State? = null)

    private val calls = HashMap<CSharpSymbol, Call?>()

    private fun invocation(call: CSharpInvocationExpression, s: State, branches: Branches?): N {
        val callee = call.expression ?: return N.UNKNOWN
        val arguments = call.argumentList?.arguments.orEmpty()
        if (callee is CSharpIdentifierName && callee.identifier?.text == "nameof" && symbolOf(callee) == null) return N.NOT
        val name: CSharpSimpleName? = when (callee) {
            is CSharpSimpleName -> callee
            is CSharpMemberAccessExpression -> callee.nameElement
            is CSharpMemberBindingExpression -> callee.nameElement
            else -> null
        }
        val resolution = name?.identifier?.let(r::resolve)
        val symbol = resolution?.single
        // a delegate: a value called
        if (name == null || symbol != null && !r.isMethod(symbol) && !(symbol is CSharpSymbol.Local && symbol.symbol.kind == LocalSymbolKind.LOCAL_FUNCTION)) {
            val value = eval(callee, s)
            dereference(callee, value, s)
            unknownArguments(arguments, s, havocAll = false)
            return N.UNKNOWN
        }
        var receiver: CSharpExpression? = null
        var receiverValue = N.NOT
        when (callee) {
            is CSharpMemberAccessExpression -> callee.expression?.let { e ->
                if (!isStaticReceiver(e)) {
                    receiver = e
                    receiverValue = eval(e, s)
                }
            }
            else -> {}
        }
        val info = symbol?.let(::callOf)
        if (info == null) {
            receiver?.let { if (symbol != null && !r.isExtension(symbol)) dereference(it, receiverValue, s) }
            // candidates that agree on having no effects on the states change nothing; else the call is not known at all
            val candidates = resolution?.symbols.orEmpty()
            val harmless = candidates.isNotEmpty() && candidates.all { c -> callOf(c)?.hasEffects == false }
            unknownArguments(arguments, s, havocAll = !harmless)
            return N.UNKNOWN
        }
        val reduced = receiver != null && r.isExtension(symbol)
        val parameters = info.parameters
        if (receiver != null && !reduced && isStatic(symbol) != true) dereference(receiver!!, receiverValue, s)
        if (reduced) {
            val first = parameters.firstOrNull()
            if (first != null) checkArgument(receiver!!, receiverValue, first, info)
        }
        val offset = if (reduced) 1 else 0
        val values = HashMap<Int, Pair<CSharpExpression, N>>()
        val outs = ArrayList<Pair<CSharpArgument, Param>>()
        if (reduced) values[0] = receiver!! to receiverValue
        for ((i, argument) in arguments.withIndex()) {
            val expression = argument.expression ?: continue
            val (index, parameter) = parameterFor(info, argument, i + offset, arguments) ?: (-1 to null)
            val kind = argument.refKindKeyword?.text
            if (kind == "out" || kind == "ref") {
                if (kind == "ref") eval(expression, s)
                if (parameter != null) outs += argument to parameter else {
                    if (expression is CSharpDeclarationExpression) declareUnknown(expression, s) else slotOf(expression)?.let { write(s, it, N.UNKNOWN) } ?: forget(expression, s)
                }
                continue
            }
            if (isFunction(expression)) continue
            val doesNotReturnIf = parameter?.annotations?.doesNotReturnIf
            val value: N
            if (doesNotReturnIf != null) {
                val (t, f) = condition(expression, s)
                assign(s, if (doesNotReturnIf) f else t)
                value = N.NOT
            } else value = eval(expression, s)
            if (parameter == null) continue
            values[index] = expression to value
            checkArgument(expression, value, parameter, info)
        }
        if (s.unreachable) return N.NOT
        // what the call says of its arguments once it returns
        for (index in values.keys) {
            val parameter = parameters.getOrNull(index) ?: continue
            val (expression, _) = values.getValue(index)
            if (parameter.annotations.notNull) learnNotNull(expression, s)
        }
        for ((argument, parameter) in outs) {
            val expression = argument.expression ?: continue
            val after = if (parameter.generic && parameter.after == N.MAYBE && !isReference(r.typeOf(expression))) N.UNKNOWN else parameter.after
            outSlot(expression, s)?.let { write(s, it, after) } ?: forget(expression, s)
        }
        val self = memberOwnerSlot(callee)
        if (self != null) for (member in info.memberNotNull) memberSlot(self, member)?.let { set(s, it, N.NOT) }
        if (branches != null) {
            val t = s.copy()
            val f = s.copy()
            for (index in values.keys) {
                val parameter = parameters.getOrNull(index) ?: continue
                val expression = values.getValue(index).first
                parameter.annotations.notNullWhen?.let { learnNotNull(expression, if (it) t else f) }
            }
            for ((argument, parameter) in outs) {
                val expression = argument.expression ?: continue
                val slot = outSlot(expression, s) ?: continue
                val reference = !parameter.generic || isReference(r.typeOf(expression))
                parameter.annotations.notNullWhen?.let { set(if (it) t else f, slot, N.NOT) }
                parameter.annotations.maybeNullWhen?.let { set(if (it) t else f, slot, if (reference) N.MAYBE else N.UNKNOWN) }
            }
            if (self != null) for ((value, members) in info.memberNotNullWhen) for (member in members) memberSlot(self, member)?.let { set(if (value) t else f, it, N.NOT) }
            branches.whenTrue = t
            branches.whenFalse = f
        }
        if (info.doesNotReturn) {
            s.unreachable = true
            return N.NOT
        }
        return returned(symbol, call, values)
    }

    /** The slot an `out` / `ref` argument writes: a variable, or the local `out var x` declares. */
    private fun outSlot(expression: CSharpExpression, s: State): Slot? {
        if (expression is CSharpDeclarationExpression) {
            val designation = expression.designation as? CSharpSingleVariableDesignation
            if (designation == null) {
                declareUnknown(expression, s)
                return null
            }
            return designation.identifier?.let(r.syntax::symbolAt)?.let { Slot(it, emptyList()) }
        }
        return slotOf(expression)
    }

    /** A call nothing is known of: its arguments may be anything after it; [havocAll] — and it may not return. */
    private fun unknownArguments(arguments: List<CSharpArgument>, s: State, havocAll: Boolean = false) {
        for (argument in arguments) {
            val expression = argument.expression ?: continue
            if (argument.refKindKeyword?.text == "out") {
                if (expression is CSharpDeclarationExpression) declareUnknown(expression, s) else slotOf(expression)?.let { write(s, it, N.UNKNOWN) } ?: forget(expression, s)
                continue
            }
            if (isFunction(expression)) continue
            eval(expression, s)
            slotOf(expression)?.let { if (argument.refKindKeyword != null) write(s, it, N.UNKNOWN) else if (read(s, it) == N.MAYBE) set(s, it, N.UNKNOWN) }
        }
        if (havocAll) havoc(s)
    }

    private fun parameterFor(info: Call, argument: CSharpArgument, position: Int, arguments: List<CSharpArgument>): Pair<Int, Param>? {
        val name = argument.nameColon?.nameElement?.identifier?.text
        val parameters = info.parameters
        if (name != null) return parameters.withIndex().firstOrNull { it.value.name == name }?.let { it.index to it.value }
        val last = parameters.lastOrNull() ?: return null
        // the expanded form of `params`: an element, not the array
        if (last.isParams && (position > parameters.size - 1 || position == parameters.size - 1 && arguments.size + (position - arguments.indexOf(argument)) != parameters.size)) return null
        return parameters.getOrNull(position)?.let { position to it }
    }

    private fun checkArgument(expression: CSharpExpression, value: N, parameter: Param, info: Call) {
        if (value != N.MAYBE || parameter.accepts != N.NOT || parameter.refKind != null && parameter.refKind != "in") return
        if (isNullConstant(expression)) warn("CS8625", CS8625, expression)
        else warn("CS8604", "Possible null reference argument for parameter '${parameter.name}' in '${info.display}'.", expression)
    }

    /** The state of what [symbol] returns at [call]: its declaration, `[NotNullIfNotNull]` of an argument. */
    private fun returned(symbol: CSharpSymbol, call: CSharpInvocationExpression, values: Map<Int, Pair<CSharpExpression, N>>): N {
        val annotations: IndexedNullability.Annotations = when (symbol) {
            is CSharpSymbol.LibraryMember -> symbol.member.nullability.returns
            is CSharpSymbol.SourceMember -> sourceReturnAnnotations(symbol.element)
            is CSharpSymbol.Local -> sourceReturnAnnotations(symbol.symbol.declaration.parent)
            else -> IndexedNullability.Annotations.NONE
        }
        annotations.notNullIfNotNull?.let { parameterName ->
            val info = callOf(symbol) ?: return N.UNKNOWN
            val index = info.parameters.indexOfFirst { it.name == parameterName }
            val argument = values[index] ?: return N.UNKNOWN
            if (argument.second == N.NOT) return N.NOT
            if (argument.second != N.MAYBE) return N.UNKNOWN
        }
        val receiver = (call.expression as? CSharpMemberAccessExpression)?.expression
        return when (symbol) {
            is CSharpSymbol.LibraryMember -> libraryDeclared(symbol.member, { r.typeOf(call) }, receiver)
            is CSharpSymbol.SourceMember -> sourceDeclared(symbol.element)
            is CSharpSymbol.Local -> (symbol.symbol.declaration.parent as? CSharpLocalFunctionStatement)?.let { f ->
                if (f.modifiers.any { it.text == "async" }) N.NOT else applyReturnAttributes(typeNullability(f.returnType, r), f.attributeLists, { f.returnType?.let(r::resolveType) })
            } ?: N.UNKNOWN
            else -> N.UNKNOWN
        }
    }

    private fun callOf(symbol: CSharpSymbol): Call? = calls.getOrPut(symbol) { computeCall(symbol) }

    private fun computeCall(symbol: CSharpSymbol): Call? = when (symbol) {
        is CSharpSymbol.LibraryMember -> libraryCall(symbol.member)
        is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
            is CSharpMethodDeclaration -> sourceCall(element, element.parameterList, element.attributeLists, display(element))
            is CSharpConstructorDeclaration -> sourceCall(element, element.parameterList, element.attributeLists, display(element))
            else -> null
        }
        is CSharpSymbol.Local -> (symbol.symbol.declaration.parent as? CSharpLocalFunctionStatement)?.let { f ->
            sourceCall(f, f.parameterList, f.attributeLists, "${f.returnType?.text.orEmpty()} ${f.identifier?.text.orEmpty()}(${f.parameterList?.parameters.orEmpty().joinToString(", ") { it.type?.text + " " + it.identifier?.text }})")
        }
        else -> null
    }

    private fun libraryCall(member: IndexedMember): Call? {
        if (!member.kind.isCallable && member.kind != IndexedMemberKind.CONSTRUCTOR) return null
        val nullability = member.nullability
        val parameters = r.session.parameters(member).mapIndexed { i, p ->
            val a = nullability.parameter(i)
            val ref = p.typeRef
            val generic = ref is IndexedTypeRef.TypeParameter
            val reference = isReferenceRef(ref)
            val accepts = when {
                a.oblivious || generic -> N.UNKNOWN
                !reference -> N.UNKNOWN
                a.allowNull -> N.MAYBE
                ref.annotated && !a.disallowNull -> N.MAYBE
                else -> N.NOT
            }
            val after = when {
                a.oblivious -> N.UNKNOWN
                a.notNull -> N.NOT
                generic -> if (a.maybeNull || ref.annotated) N.MAYBE else N.UNKNOWN
                !reference -> N.NOT
                a.maybeNull || ref.annotated -> N.MAYBE
                else -> N.NOT
            }
            val kind = if (p.isOut) "out" else if (p.isRef) "ref" else if (p.isIn) "in" else null
            Param(p.name, kind, p.isParams, accepts, after, a, generic)
        }
        return Call(parameters, nullability.doesNotReturn || "System.Diagnostics.CodeAnalysis.DoesNotReturnAttribute" in member.attributes,
            nullability.memberNotNull, nullability.memberNotNullWhen, member.toString())
    }

    private fun sourceCall(function: PsiElement, list: CSharpParameterList?, attributeLists: List<CSharpAttributeList>, display: String): Call? {
        val resolver = (function.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
        val parameters = list?.parameters.orEmpty().map { p ->
            val modifiers = p.modifiers.map { it.text }
            val a = annotationsOf(p.attributeLists, null)
            val type = p.type
            val resolved = type?.let(resolver::resolveType)
            val generic = resolved is SemanticType.Parameter || type is CSharpNullableType && type.elementType?.let(resolver::resolveType) is SemanticType.Parameter
            val declared = typeNullability(type, resolver)
            val accepts = when {
                generic || declared == N.UNKNOWN -> N.UNKNOWN
                !isReference(if (type is CSharpNullableType) type.elementType?.let(resolver::resolveType) else resolved) -> N.UNKNOWN
                a.allowNull -> N.MAYBE
                declared == N.MAYBE && !a.disallowNull -> N.MAYBE
                else -> N.NOT
            }
            val after = when {
                a.notNull -> N.NOT
                a.maybeNull -> if (generic || declared != N.UNKNOWN) N.MAYBE else N.UNKNOWN
                else -> declared
            }
            val kind = when {
                "out" in modifiers -> "out"
                "ref" in modifiers -> "ref"
                "in" in modifiers || "readonly" in modifiers -> "in"
                else -> null
            }
            Param(p.identifier?.text.orEmpty(), kind, "params" in modifiers, accepts, after, a, generic)
        }
        val member = memberAttributes(attributeLists)
        return Call(parameters, member.doesNotReturn, member.notNull, member.notNullWhen, display)
    }

    private fun display(method: CSharpBaseMethodDeclaration): String {
        val type = PsiTreeUtil.getParentOfType(method, CSharpBaseTypeDeclaration::class.java)?.identifier?.text.orEmpty()
        val parameters = method.parameterList?.parameters.orEmpty().joinToString(", ") { listOfNotNull(it.type?.text, it.identifier?.text).joinToString(" ") }
        return when (method) {
            is CSharpMethodDeclaration -> "${method.returnType?.text.orEmpty()} $type.${method.identifier?.text.orEmpty()}($parameters)"
            else -> "$type.$type($parameters)"
        }
    }

    /** The member a `[MemberNotNull]` of the method called by [callee] names, on [self]. */
    private fun memberSlot(self: Pair<Slot?, SemanticType>, name: String): Slot? {
        val (owner, type) = self
        val symbol = r.membersNamed(type, name, 0).singleOrNull() ?: return null
        if (!isValueMember(symbol)) return null
        if (isStatic(symbol) == true) return Slot(Static, listOf(step(symbol, null, null)))
        if (owner == null || owner.steps.size >= MAX_DEPTH) return null
        return owner.child(step(symbol, null, null))
    }

    /**
     * Whose members `[MemberNotNull]` of a call through [callee] names (as Roslyn, of any receiver: `g.Init()` sets `g.Name`): its slot
     * (null for a type, whose static members only it may name) and its type.
     */
    private fun memberOwnerSlot(callee: CSharpExpression): Pair<Slot?, SemanticType>? {
        val self = { r.syntax.enclosingTypes(callee).firstOrNull()?.let(r::selfType) }
        return when (callee) {
            is CSharpSimpleName -> self()?.let { Slot(This, emptyList()) to it }
            is CSharpMemberAccessExpression -> when (val receiver = callee.expression?.let(::strip)) {
                null -> null
                is CSharpThisExpression, is CSharpBaseExpression -> self()?.let { Slot(This, emptyList()) to it }
                else -> {
                    val type = (if (isStaticReceiver(receiver)) (receiver as? CSharpType)?.let(r::resolveType) ?: r.typeOf(receiver) else r.typeOf(receiver)) ?: return null
                    (if (isStaticReceiver(receiver)) null else slotOf(receiver)) to type
                }
            }
            else -> null
        }
    }

    // ---- conditions

    /** The states when [e] is true and when it is false. */
    private fun condition(e: CSharpExpression?, s: State): Pair<State, State> {
        if (e == null || s.unreachable) return s to s.copy()
        ProgressManager.checkCanceled()
        when (e) {
            is CSharpParenthesizedExpression -> return condition(e.expression, s)
            is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text == "!") return condition(e.operand, s).let { (t, f) -> f to t }
            is CSharpLiteralExpression -> when (e.elementType) {
                SyntaxKind.TrueLiteralExpression -> return s to bottom()
                SyntaxKind.FalseLiteralExpression -> return bottom() to s
            }
            is CSharpBinaryExpression -> when (e.operatorToken?.text) {
                "&&" -> {
                    val (leftTrue, leftFalse) = condition(e.left, s)
                    val (rightTrue, rightFalse) = condition(e.right, leftTrue)
                    return rightTrue to join(leftFalse, rightFalse)
                }
                "||" -> {
                    val (leftTrue, leftFalse) = condition(e.left, s)
                    val (rightTrue, rightFalse) = condition(e.right, leftFalse)
                    return join(leftTrue, rightTrue) to rightFalse
                }
                "==", "!=" -> return equality(e, s)
                "is" -> {
                    val tested = e.left ?: return s to s.copy()
                    eval(tested, s)
                    val t = s.copy()
                    learnNotNull(tested, t)
                    return t to s.copy()
                }
            }
            is CSharpIsPatternExpression -> return pattern(e, s)
            is CSharpIdentifierName, is CSharpMemberAccessExpression -> propertyCondition(e, s)?.let { return it }
            is CSharpInvocationExpression -> {
                val branches = Branches()
                invocation(e, s, branches)
                val t = branches.whenTrue
                val f = branches.whenFalse
                if (s.unreachable) return s to s.copy()
                return if (t != null && f != null) t to f else s to s.copy()
            }
        }
        eval(e, s)
        unsure(e, s)
        return s to s.copy()
    }

    /** A bool property with `[MemberNotNullWhen]` (`if (g.HasName)`): the members it names are not null in its branch. */
    private fun propertyCondition(e: CSharpExpression, s: State): Pair<State, State>? {
        val name = when (e) {
            is CSharpIdentifierName -> e
            is CSharpMemberAccessExpression -> e.nameElement ?: return null
            else -> return null
        }
        val whenTrue: Map<Boolean, List<String>> = when (val symbol = symbolOf(name)) {
            is CSharpSymbol.LibraryMember -> symbol.member.takeIf { it.kind == IndexedMemberKind.PROPERTY }?.nullability?.memberNotNullWhen
            is CSharpSymbol.SourceMember -> (symbol.element as? CSharpPropertyDeclaration)?.let { p ->
                memberAttributes(p.attributeLists + p.accessorList?.accessors.orEmpty().filter { it.keyword?.text == "get" }.flatMap { it.attributeLists }).notNullWhen
            }
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return null
        eval(e, s)
        if (s.unreachable) return s to s.copy()
        val self = memberOwnerSlot(e) ?: return s to s.copy()
        val t = s.copy()
        val f = s.copy()
        for ((value, members) in whenTrue) for (member in members) memberSlot(self, member)?.let { set(if (value) t else f, it, N.NOT) }
        return t to f
    }

    /** A condition the analysis does not read:whatever it names that was maybe-null may be anything in both branches (`x?.Length > 0`). */
    private fun unsure(e: CSharpExpression, s: State) {
        if (s.unreachable) return
        PsiTreeUtil.processElements(e) { element ->
            if (element is CSharpIdentifierName || element is CSharpMemberAccessExpression || element is CSharpMemberBindingExpression) {
                (if (element is CSharpMemberBindingExpression) null else slotOf(element as CSharpExpression))?.let { if (read(s, it) == N.MAYBE) set(s, it, N.UNKNOWN) }
            }
            element !is CSharpAnonymousFunctionExpression
        }
    }

    private fun equality(e: CSharpBinaryExpression, s: State): Pair<State, State> {
        val left = e.left
        val right = e.right
        val tested = when {
            right != null && isNullConstant(right) && !isNullConstant(left) -> left
            left != null && isNullConstant(left) -> right
            else -> null
        }
        if (tested == null) {
            eval(left, s)
            eval(right, s)
            unsure(e, s)
            return s to s.copy()
        }
        eval(tested, s)
        if (s.unreachable) return s to s.copy()
        val isNull = s.copy()
        val notNull = s.copy()
        learnMaybeNull(tested, isNull)
        learnNotNull(tested, notNull)
        return if (e.operatorToken?.text == "==") isNull to notNull else notNull to isNull
    }

    private fun pattern(e: CSharpIsPatternExpression, s: State): Pair<State, State> {
        val tested = e.expression ?: return s to s.copy()
        val input = eval(tested, s)
        val p = e.pattern ?: return s to s.copy()
        if (s.unreachable) return s to s.copy()
        val t = s.copy()
        val f = s.copy()
        when (testOf(p)) {
            Test.NULL -> {
                learnMaybeNull(tested, t)
                learnNotNull(tested, f)
            }
            Test.NOT_NULL -> {
                learnNotNull(tested, t)
                learnMaybeNull(tested, f)
            }
            Test.NON_NULL_MATCH -> {
                learnNotNull(tested, t)
                slotOf(tested)?.let { if (read(f, it) != N.MAYBE) set(f, it, N.UNKNOWN) }
            }
            Test.NOT_NON_NULL_MATCH -> {
                learnNotNull(tested, f)
                slotOf(tested)?.let { if (read(t, it) != N.MAYBE) set(t, it, N.UNKNOWN) }
            }
            Test.ALWAYS -> f.unreachable = true
            Test.OTHER -> slotOf(tested)?.let {
                set(t, it, N.UNKNOWN)
                set(f, it, N.UNKNOWN)
            }
        }
        declarePattern(p, t, input)
        return t to f
    }

    private enum class Test { NULL, NOT_NULL, NON_NULL_MATCH, NOT_NON_NULL_MATCH, ALWAYS, OTHER }

    private fun testOf(p: CSharpPattern): Test = when (p) {
        is CSharpParenthesizedPattern -> p.pattern?.let(::testOf) ?: Test.OTHER
        is CSharpConstantPattern -> if (p.expression?.let(::isNullConstant) == true) Test.NULL else Test.OTHER
        is CSharpUnaryPattern -> when (p.pattern?.let(::testOf)) {
            Test.NULL -> Test.NOT_NULL
            Test.NON_NULL_MATCH -> Test.NOT_NON_NULL_MATCH
            else -> Test.OTHER
        }
        is CSharpDeclarationPattern, is CSharpTypePattern -> Test.NON_NULL_MATCH
        is CSharpRecursivePattern -> Test.NON_NULL_MATCH
        is CSharpVarPattern, is CSharpDiscardPattern -> Test.ALWAYS
        else -> Test.OTHER
    }

    /** The variables [p] declares, once it matched [input]: a typed one is not null, the others are of no known state. */
    private fun declarePattern(p: CSharpPattern, s: State, input: N) {
        fun designate(designation: CSharpVariableDesignation?, value: N) {
            when (designation) {
                is CSharpSingleVariableDesignation -> designation.identifier?.let(r.syntax::symbolAt)?.let { write(s, Slot(it, emptyList()), value) }
                is CSharpParenthesizedVariableDesignation -> designation.variables.forEach { designate(it, N.UNKNOWN) }
                else -> {}
            }
        }
        fun walk(pattern: CSharpPattern?, value: N, top: Boolean) {
            when (pattern) {
                is CSharpDeclarationPattern -> designate(pattern.designation, N.NOT)
                is CSharpRecursivePattern -> {
                    designate(pattern.designation, N.NOT)
                    pattern.positionalPatternClause?.subpatterns?.forEach { walk(it.pattern, N.UNKNOWN, false) }
                    pattern.propertyPatternClause?.subpatterns?.forEach { walk(it.pattern, N.UNKNOWN, false) }
                }
                is CSharpVarPattern -> designate(pattern.designation, if (top) value else N.UNKNOWN)
                is CSharpParenthesizedPattern -> walk(pattern.pattern, value, top)
                is CSharpBinaryPattern -> {
                    walk(pattern.left, N.UNKNOWN, false)
                    walk(pattern.right, N.UNKNOWN, false)
                }
                is CSharpUnaryPattern -> walk(pattern.pattern, N.UNKNOWN, false)
                is CSharpListPattern -> pattern.patterns.forEach { walk(it, N.UNKNOWN, false) }
                else -> {}
            }
        }
        walk(p, input, true)
    }

    /** [e] is not null here: its slot, and through `?.`, a cast, an assignment, the variables it is made of. */
    private fun learnNotNull(e: CSharpExpression?, s: State) {
        e ?: return
        if (s.unreachable) return
        when (val x = strip(e)) {
            is CSharpCastExpression -> learnNotNull(x.expression, s)
            is CSharpPostfixUnaryExpression -> if (x.operatorToken?.text == "!") learnNotNull(x.operand, s)
            is CSharpAssignmentExpression -> if (x.operatorToken?.text == "=") learnNotNull(x.left, s)
            is CSharpConditionalAccessExpression -> {
                learnNotNull(x.expression, s)
                val receiver = slotOf(x.expression) ?: return
                // `a?.b` not null: `a.b` is not null either
                val binding = x.whenNotNull as? CSharpMemberBindingExpression ?: return
                val symbol = binding.nameElement?.let(::symbolOf) ?: return
                if (isValueMember(symbol) && receiver.steps.size < MAX_DEPTH) set(s, receiver.child(step(symbol, null, binding)), N.NOT)
            }
            else -> slotOf(x)?.let { set(s, it, N.NOT) }
        }
    }

    /** [e] was compared with null and found equal: maybe null (a pure null test teaches Roslyn that even of a non-nullable variable). */
    private fun learnMaybeNull(e: CSharpExpression?, s: State) {
        e ?: return
        slotOf(strip(e))?.let { set(s, it, N.MAYBE) }
    }

    /** `null`, `default`, `(T)null` — a null constant, not `null!`. */
    private fun isNullConstant(e: CSharpExpression?): Boolean = when (e) {
        is CSharpLiteralExpression -> e.elementType == SyntaxKind.NullLiteralExpression || e.elementType == SyntaxKind.DefaultLiteralExpression
        is CSharpParenthesizedExpression -> isNullConstant(e.expression)
        is CSharpCastExpression -> isNullConstant(e.expression) && e.type !is CSharpNullableType
        else -> false
    }

    // ---- names and slots

    private fun symbolOf(name: CSharpSimpleName): CSharpSymbol? = name.identifier?.let(r::resolve)?.single

    /** A field, a property, an event: a member whose value is read (not a method, a type, a constant). */
    private fun isValueMember(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
            is CSharpVariableDeclarator -> (element.parent?.parent as? CSharpBaseFieldDeclaration)?.modifiers?.none { it.text == "const" } == true
            is CSharpBaseFieldDeclaration -> element.modifiers.none { it.text == "const" }
            is CSharpPropertyDeclaration, is CSharpEventDeclaration -> true
            else -> element.parent is CSharpParameter
        }
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.FIELD || symbol.member.kind == IndexedMemberKind.PROPERTY || symbol.member.kind == IndexedMemberKind.EVENT
        else -> false
    }

    private fun isStatic(symbol: CSharpSymbol): Boolean? = r.overloads.isStatic(symbol)

    private fun memberKey(symbol: CSharpSymbol): Any? = when (symbol) {
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpBaseFieldDeclaration)?.declaration?.variables?.singleOrNull() ?: symbol.element
        is CSharpSymbol.LibraryMember -> symbol.member
        else -> null
    }

    private fun memberName(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.name
        is CSharpSymbol.SourceMember -> when (val element = memberKey(symbol)) {
            is CSharpVariableDeclarator -> element.identifier?.text
            is CSharpPropertyDeclaration -> element.identifier?.text
            is CSharpEventDeclaration -> element.identifier?.text
            else -> null
        }
        else -> null
    }

    /**
     * A write the analysis cannot place (a name that resolves to no single member, `x.F` of an unknown `x`): every path of `this` and of
     * static members that ends in a member of that name may now be anything.
     */
    private fun forget(target: CSharpExpression, s: State) {
        val name = when (val t = strip(target)) {
            is CSharpIdentifierName -> t.identifier?.text
            is CSharpMemberAccessExpression -> t.nameElement?.identifier?.text
            else -> null
        } ?: return
        if (s.unreachable) return
        for (entry in s.values.entries) {
            val slot = entry.key
            if (entry.value == N.MAYBE && slot.steps.lastOrNull()?.name == name) entry.setValue(N.UNKNOWN)
        }
    }

    private fun step(symbol: CSharpSymbol,receiver: CSharpExpression?, at: CSharpExpression?): Step =
        Step(memberKey(symbol) ?: symbol, memberName(symbol)) { memberDeclared(symbol, receiver, at) }

    /** Whether [receiver] of `A.B` is a type or a namespace, not a value. */
    private fun isStaticReceiver(receiver: CSharpExpression): Boolean {
        val name = when (val x = strip(receiver)) {
            is CSharpPredefinedType, is CSharpQualifiedName, is CSharpAliasQualifiedName -> return true
            is CSharpSimpleName -> x
            is CSharpMemberAccessExpression -> x.nameElement ?: return false
            else -> return false
        }
        return when (val symbol = symbolOf(name)) {
            is CSharpSymbol.Namespace, is CSharpSymbol.SourceType, is CSharpSymbol.LibraryType -> true
            is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.TYPE_PARAMETER
            else -> false
        }
    }

    private fun own(local: LocalSymbol): Boolean = PsiTreeUtil.isAncestor(fn.owner, local.declaration, true)

    private fun slotOf(e: CSharpExpression?): Slot? {
        val x = e?.let(::strip) ?: return null
        return when (x) {
            // `x!.F = v`: the path is `x.F`
            is CSharpPostfixUnaryExpression -> if (x.operatorToken?.text == "!") slotOf(x.operand) else null
            is CSharpThisExpression, is CSharpBaseExpression -> Slot(This, emptyList())
            is CSharpIdentifierName -> {
                when (val symbol = symbolOf(x) ?: return null) {
                    is CSharpSymbol.Local -> if ((symbol.symbol.kind == LocalSymbolKind.LOCAL || symbol.symbol.kind == LocalSymbolKind.PARAMETER) && own(symbol.symbol)) Slot(symbol.symbol, emptyList()) else null
                    is CSharpSymbol.SourceMember, is CSharpSymbol.LibraryMember -> {
                        if (!isValueMember(symbol)) return null
                        Slot(if (isStatic(symbol) == true) Static else This, listOf(step(symbol, null, x)))
                    }
                    else -> null
                }
            }
            is CSharpMemberAccessExpression -> {
                val receiver = x.expression ?: return null
                val symbol = x.nameElement?.let(::symbolOf) ?: return null
                if (!isValueMember(symbol)) return null
                if (isStaticReceiver(receiver)) return if (isStatic(symbol) == true) Slot(Static, listOf(step(symbol, null, x))) else null
                if (isStatic(symbol) == true) return null
                val base = slotOf(receiver) ?: return null
                if (base.steps.size >= MAX_DEPTH) return null
                base.child(step(symbol, receiver, x))
            }
            is CSharpMemberBindingExpression -> {
                val base = fn.bindings.lastOrNull() ?: return null
                val symbol = x.nameElement?.let(::symbolOf) ?: return null
                if (!isValueMember(symbol) || isStatic(symbol) == true || base.steps.size >= MAX_DEPTH) return null
                base.child(step(symbol, null, x))
            }
            else -> null
        }
    }

    // ---- what declarations say

    private fun declared(slot: Slot): N {
        if (slot.steps.isEmpty()) return when (val root = slot.root) {
            This -> N.NOT
            is LocalSymbol -> if (root.kind == LocalSymbolKind.PARAMETER) parameterDeclared(root) else N.UNKNOWN
            else -> N.UNKNOWN
        }
        // inside a lambda or a local function, what the outer function knows of `this` and of static members is not carried in
        if (fn.nested && (slot.root === This || slot.root === Static)) return N.UNKNOWN
        return slot.steps.last().declared
    }

    private val parameters = HashMap<LocalSymbol, N>()

    private fun parameterDeclared(local: LocalSymbol): N = parameters.getOrPut(local) {
        val parameter = local.declaration.parent as? CSharpParameter ?: return@getOrPut N.UNKNOWN
        val modifiers = parameter.modifiers.map { it.text }
        if ("out" in modifiers) return@getOrPut N.UNKNOWN
        if ("params" in modifiers) return@getOrPut N.NOT
        val declared = typeNullability(parameter.type, r)
        val a = annotationsOf(parameter.attributeLists, null)
        when {
            declared == N.UNKNOWN -> N.UNKNOWN
            a.disallowNull -> N.NOT
            a.allowNull -> if (isReference(parameter.type?.let(r::resolveType))) N.MAYBE else N.UNKNOWN
            else -> declared
        }
    }

    private fun memberDeclared(symbol: CSharpSymbol, receiver: CSharpExpression?, at: CSharpExpression?): N = when (symbol) {
        is CSharpSymbol.LibraryMember -> libraryDeclared(symbol.member, { at?.let(r::typeOf) }, receiver)
        is CSharpSymbol.SourceMember -> sourceDeclared(symbol.element)
        else -> N.UNKNOWN
    }

    /**
     * What a member of an assembly gives: its annotation (`string?`), `[MaybeNull]` / `[NotNull]`, nothing for an oblivious one; a type
     * parameter `T?` is maybe null when [resultType] is a reference type. A virtual member reached through another type than its own may be
     * overridden with another annotation: maybe-null is then not sure.
     */
    private fun libraryDeclared(member: IndexedMember, resultType: () -> SemanticType?, receiver: CSharpExpression?): N {
        val annotations = member.nullability.returns
        if (annotations.oblivious) return N.UNKNOWN
        if (annotations.notNull) return N.NOT
        var ref = member.typeRef
        if (ref is IndexedTypeRef.ByRef) ref = ref.element
        val value = when {
            ref is IndexedTypeRef.TypeParameter -> if (annotations.maybeNull || ref.annotated) (if (isReference(resultType())) N.MAYBE else N.UNKNOWN) else N.UNKNOWN
            !isReferenceRef(ref) -> N.NOT
            annotations.maybeNull || ref.annotated -> N.MAYBE
            else -> N.NOT
        }
        if (value == N.MAYBE && (member.isVirtual || member.isAbstract || member.isOverride) && member.type.kind != IndexedTypeKind.INTERFACE) {
            val type = receiver?.let(r::typeOf)
            if (type !is SemanticType.Library || type.type != member.type) return N.UNKNOWN
        }
        return value
    }

    private fun isReferenceRef(ref: IndexedTypeRef): Boolean = when (ref) {
        is IndexedTypeRef.Named -> !ref.isValueType
        is IndexedTypeRef.Generic -> !ref.definition.isValueType
        is IndexedTypeRef.ArrayOf -> true
        else -> false
    }

    private val sources = HashMap<PsiElement, N>()

    /** What a field, property, event or method of the solution gives, by its written type and its nullable attributes. */
    private fun sourceDeclared(element: PsiElement): N = sources.getOrPut(element) {
        val resolver = (element.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return@getOrPut N.UNKNOWN
        when (element) {
            is CSharpBaseFieldDeclaration -> element.declaration?.variables?.singleOrNull()?.let(::sourceDeclared) ?: N.UNKNOWN
            is CSharpVariableDeclarator -> {
                val field = element.parent?.parent as? CSharpBaseFieldDeclaration ?: return@getOrPut N.UNKNOWN
                if (field.modifiers.any { it.text == "const" }) return@getOrPut N.NOT
                val type = (element.parent as? CSharpVariableDeclaration)?.type
                applyAttributes(typeNullability(type, resolver), field.attributeLists, null) { type?.let(resolver::resolveType) }
            }
            is CSharpPropertyDeclaration -> applyAttributes(typeNullability(element.type, resolver), element.attributeLists, null) { element.type?.let(resolver::resolveType) }
            is CSharpEventDeclaration -> typeNullability(element.type, resolver)
            is CSharpIndexerDeclaration -> applyAttributes(typeNullability(element.type, resolver), element.attributeLists, null) { element.type?.let(resolver::resolveType) }
            is CSharpMethodDeclaration -> if (element.modifiers.any { it.text == "async" }) N.NOT
                else applyReturnAttributes(typeNullability(element.returnType, resolver), element.attributeLists) { element.returnType?.let(resolver::resolveType) }
            else -> (element.parent as? CSharpParameter)?.let { typeNullability(it.type, resolver) } ?: N.UNKNOWN
        }
    }

    private fun applyReturnAttributes(declared: N, lists: List<CSharpAttributeList>, type: () -> SemanticType?): N {
        val a = annotationsOf(lists, "return")
        if (a.notNullIfNotNull != null || a.notNullWhen != null || a.maybeNullWhen != null) return N.UNKNOWN
        return applyAttributes(declared, emptyList(), a, type)
    }

    /** [declared] changed by `[MaybeNull]` / `[NotNull]` of [lists] (or of [given]); any conditional attribute makes it unknown. */
    private fun applyAttributes(declared: N, lists: List<CSharpAttributeList>, given: IndexedNullability.Annotations?, type: () -> SemanticType?): N {
        val a = given ?: annotationsOf(lists, null)
        return when {
            a.notNull -> N.NOT
            a.maybeNull -> if (isReference(type())) N.MAYBE else N.UNKNOWN
            a.notNullWhen != null || a.maybeNullWhen != null || a.notNullIfNotNull != null -> N.UNKNOWN
            else -> declared
        }
    }

    private fun sourceReturnAnnotations(element: PsiElement?): IndexedNullability.Annotations = when (element) {
        is CSharpMethodDeclaration -> annotationsOf(element.attributeLists, "return")
        is CSharpLocalFunctionStatement -> annotationsOf(element.attributeLists, "return")
        else -> IndexedNullability.Annotations.NONE
    }

    /** What member [element] of an assignment target takes: [N.NOT] — a non-nullable reference type without `[AllowNull]`. */
    private fun memberAccepts(symbol: CSharpSymbol): N = when (symbol) {
        is CSharpSymbol.LibraryMember -> {
            val member = symbol.member
            val a = member.nullability.returns
            val ref = member.typeRef
            when {
                a.oblivious || a.allowNull || ref is IndexedTypeRef.TypeParameter || !isReferenceRef(ref) -> N.UNKNOWN
                ref.annotated && !a.disallowNull -> N.MAYBE
                else -> N.NOT
            }
        }
        is CSharpSymbol.SourceMember -> {
            val element = (symbol.element as? CSharpBaseFieldDeclaration)?.declaration?.variables?.singleOrNull() ?: symbol.element
            val resolver = (element.containingFile as? CSharpFile)?.let(r.session::reachable)
            val (type, lists) = when (element) {
                is CSharpVariableDeclarator -> (element.parent as? CSharpVariableDeclaration)?.type to (element.parent?.parent as? CSharpBaseFieldDeclaration)?.attributeLists.orEmpty()
                is CSharpPropertyDeclaration -> element.type to (element.attributeLists + element.accessorList?.accessors.orEmpty().flatMap { it.attributeLists })
                else -> null to emptyList()
            }
            if (resolver == null || type == null || hasNullableAttributes(lists)) N.UNKNOWN
            else if (typeNullability(type, resolver) == N.NOT && isReference(resolver.resolveType(type))) N.NOT else N.UNKNOWN
        }
        else -> N.UNKNOWN
    }

    /**
     * [type] as written where it is declared: a non-nullable reference type or a value type is [N.NOT], `T?` of a reference type [N.MAYBE];
     * `var`, a type parameter, `dynamic`, a type that does not resolve, a place without nullable annotations — [N.UNKNOWN].
     */
    private fun typeNullability(type: CSharpType?, resolver: CSharpNameResolver?): N {
        if (type == null || resolver == null) return N.UNKNOWN
        if (type is CSharpRefType) return typeNullability(type.type, resolver)
        if (resolver.isVar(type) || type.text == "dynamic") return N.UNKNOWN
        val containing = type.containingFile ?: return N.UNKNOWN
        if (!CSharpWarningContext.nullableAt(containing, type.textRange.startOffset).annotations) return N.UNKNOWN
        if (type is CSharpNullableType) {
            val element = type.elementType?.let(resolver::resolveType) ?: return N.UNKNOWN
            return when {
                element is SemanticType.Parameter -> N.UNKNOWN
                isReference(element) -> N.MAYBE
                r.isValueType(element) -> N.NOT
                else -> N.UNKNOWN
            }
        }
        val resolved = resolver.resolveType(type) ?: return N.UNKNOWN
        return when {
            resolved is SemanticType.Parameter -> N.UNKNOWN
            isReference(resolved) -> N.NOT
            r.isValueType(resolved) -> N.NOT
            else -> N.UNKNOWN
        }
    }

    private fun isReference(type: SemanticType?): Boolean = when (type) {
        null, is SemanticType.Parameter -> false
        is SemanticType.ArrayOf -> true
        is SemanticType.Source -> type.info.kind in REFERENCE_KINDS
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.CLASS || type.type.kind == IndexedTypeKind.INTERFACE || type.type.kind == IndexedTypeKind.DELEGATE
    }

    // ---- attributes of the source

    private class MemberAttributes(val doesNotReturn: Boolean, val notNull: List<String>, val notNullWhen: Map<Boolean, List<String>>)

    private fun attributeName(attribute: CSharpAttribute): String? {
        val name = when (val n = attribute.nameElement) {
            is CSharpSimpleName -> n.identifier?.text
            is CSharpQualifiedName -> n.right?.identifier?.text
            is CSharpAliasQualifiedName -> n.nameElement?.identifier?.text
            else -> null
        } ?: return null
        return name.removeSuffix("Attribute")
    }

    private fun lists(lists: List<CSharpAttributeList>, target: String?): List<CSharpAttribute> =
        lists.filter { it.target?.identifier?.text == target }.flatMap { it.attributes }

    private fun hasAttribute(lists: List<CSharpAttributeList>, name: String): Boolean = lists.any { list -> list.attributes.any { attributeName(it) == name } }

    private fun hasNullableAttributes(lists: List<CSharpAttributeList>): Boolean = lists.any { list -> list.attributes.any { attributeName(it) in NULLABLE_ATTRIBUTES } }

    /** The nullable attributes of a parameter or (with [target] `return`) of a return value, as the index has them. */
    private fun annotationsOf(lists: List<CSharpAttributeList>, target: String?): IndexedNullability.Annotations {
        var a = IndexedNullability.Annotations.NONE
        for (attribute in lists(lists, target)) {
            val arguments = attribute.argumentList?.arguments.orEmpty().map { it.expression }
            val code = when (attributeName(attribute)) {
                "AllowNull" -> "A"
                "DisallowNull" -> "D"
                "MaybeNull" -> "M"
                "NotNull" -> "N"
                "NotNullWhen" -> boolOf(arguments.firstOrNull())?.let { "W" + if (it) "1" else "0" }
                "MaybeNullWhen" -> boolOf(arguments.firstOrNull())?.let { "w" + if (it) "1" else "0" }
                "DoesNotReturnIf" -> boolOf(arguments.firstOrNull())?.let { "X" + if (it) "1" else "0" }
                "NotNullIfNotNull" -> stringOf(arguments.firstOrNull())?.let { "I=$it" }
                else -> null
            } ?: continue
            a = a.with(code)
        }
        return a
    }

    private fun memberAttributes(lists: List<CSharpAttributeList>): MemberAttributes {
        var doesNotReturn = false
        val notNull = ArrayList<String>()
        val notNullWhen = HashMap<Boolean, List<String>>()
        for (attribute in lists(lists, null)) {
            val arguments = attribute.argumentList?.arguments.orEmpty().map { it.expression }
            when (attributeName(attribute)) {
                "DoesNotReturn" -> doesNotReturn = true
                "MemberNotNull" -> arguments.mapNotNullTo(notNull, ::stringOf)
                "MemberNotNullWhen" -> boolOf(arguments.firstOrNull())?.let { b -> notNullWhen.merge(b, arguments.drop(1).mapNotNull(::stringOf)) { x, y -> x + y } }
            }
        }
        return MemberAttributes(doesNotReturn, notNull, notNullWhen)
    }

    private fun boolOf(e: CSharpExpression?): Boolean? = when (e?.elementType) {
        SyntaxKind.TrueLiteralExpression -> true
        SyntaxKind.FalseLiteralExpression -> false
        else -> null
    }

    /** `"Name"` or `nameof(Name)` / `nameof(this.Name)`. */
    private fun stringOf(e: CSharpExpression?): String? = when (e) {
        is CSharpLiteralExpression -> e.text.takeIf { e.elementType == SyntaxKind.StringLiteralExpression && it.startsWith("\"") && it.endsWith("\"") && it.length >= 2 }?.let { it.substring(1, it.length - 1) }
        is CSharpInvocationExpression -> if ((e.expression as? CSharpIdentifierName)?.identifier?.text == "nameof") {
            when (val argument = e.argumentList?.arguments?.singleOrNull()?.expression) {
                is CSharpIdentifierName -> argument.identifier?.text
                is CSharpMemberAccessExpression -> argument.nameElement?.identifier?.text
                else -> null
            }
        } else null
        else -> null
    }

    companion object {
        private const val MAX_DEPTH = 3
        private const val CS8600 = "Converting null literal or possible null value to non-nullable type."
        private const val CS8601 = "Possible null reference assignment."
        private const val CS8625 = "Cannot convert null literal to non-nullable reference type."
        private val REFERENCE_KINDS = setOf(TypeKind.CLASS, TypeKind.STATIC_CLASS, TypeKind.RECORD, TypeKind.INTERFACE, TypeKind.DELEGATE)
        private val NULLABLE_ATTRIBUTES = setOf(
            "AllowNull", "DisallowNull", "MaybeNull", "NotNull", "MaybeNullWhen", "NotNullWhen", "NotNullIfNotNull", "MemberNotNull", "MemberNotNullWhen",
        )
    }
}
