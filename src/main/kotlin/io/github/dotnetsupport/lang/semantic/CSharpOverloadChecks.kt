package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.Member
import io.github.dotnetsupport.lang.TypeKind
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Outcome

/**
 * The errors of overload resolution and lambdas, as Roslyn reports them, for [CSharpSemanticChecks]:
 *
 * - CS0121: two or more applicable candidates and none better than all others (§12.6.4) — generic ones by strict inference
 *   ([CSharpTypeInference]), `params` in normal and expanded form, optional and `in` / `ref` parameters with their tie-breaks, lambdas and
 *   method groups by the better conversion from expression, extension methods of one scope, base methods dropped when a more derived one
 *   applies; Roslyn names the first two in its order of candidates (the derived type's first, an override where it overrides);
 * - with no applicable candidate, the one Roslyn reports, by its priorities (a constraint broken: other errors; bad arguments before type
 *   inference that failed) and in its order: CS1503 for each argument that does not convert (the receiver of an extension method counts),
 *   the errors of a lambda against its parameter (CS1593, CS1660, CS1661 + CS1678, CS1676 / CS1677), CS0411 when inference failed;
 * - a lambda against the delegate it converts to elsewhere — a variable or property of a written type, an assignment, `+=` of an event or
 *   a delegate, a cast, a `return` — the number, `ref` kinds and types of its parameters, a body whose end returns nothing (CS1643).
 *
 * Precision first, as the rest of the pass: a candidate whose applicability, inference or constraints are not surely known, an argument
 * of an unknown type (`dynamic` among them), named arguments, a lambda whose body would decide, library methods whose message would need
 * nullable annotations the index does not keep, an order of candidates that is not known (extension classes of several files) — nothing.
 */
internal class CSharpOverloadChecks(private val checks: CSharpSemanticChecks, private val r: CSharpNameResolver) {
    private val overloads = r.overloads
    private val inference = CSharpTypeInference(r)
    private val analyses = HashMap<CSharpInvocationExpression, Analysis?>()

    fun visit(element: PsiElement) {
        when (element) {
            is CSharpInvocationExpression -> checkCall(element)
            is CSharpAnonymousFunctionExpression -> checkLambda(element)
        }
    }

    // ---- what overload resolution makes of a call

    /**
     * A candidate of a call: [shown] is the method Roslyn names (the one an override overrides), [depth] the distance of the type that
     * declares [shown] from the type the members are looked up in, [reduced] an extension method called on a receiver.
     */
    private class Candidate(val symbol: CSharpSymbol, val shown: CSharpSymbol, val depth: Int, val reduced: Boolean)

    /** The candidates overload resolution looks at together — the methods of the type, the extension methods of one scope — in Roslyn's order when [ordered]. */
    private class Group(val candidates: List<Candidate>, val ordered: Boolean)

    private class Analysis(
        val callee: CSharpSimpleName, val arguments: List<CSharpArgument>,
        /** The only candidate of the call (no overload, no extension method), asked only when needed: it looks for extension methods. */
        private val only: () -> Candidate?,
        /** CS0121: the first two of the best members. */
        val ambiguous: Pair<Candidate, Candidate>? = null,
        /** No candidate applies: the one Roslyn reports, and why it does not apply. */
        val reported: Candidate? = null, val outcome: Outcome? = null,
    ) {
        val single: Candidate? by lazy(LazyThreadSafetyMode.NONE) { only() }
    }

    /** The methods of the type a call looks in, and the scopes of extension methods it goes on to when none of them applies. */
    private class Groups(val members: Group, val extensions: Lazy<List<Group>?>?) {
        /** Every group in the order they are tried; null when the extension methods are not all known. */
        fun all(): List<Group>? = listOf(members) + (extensions?.let { it.value ?: return null }.orEmpty())
    }

    private fun analysis(call: CSharpInvocationExpression): Analysis? = if (call in analyses) analyses[call] else analyze(call).also { analyses[call] = it }

    private fun analyze(call: CSharpInvocationExpression): Analysis? {
        val callee = calleeOf(call) ?: return null
        val leaf = callee.identifier ?: return null
        val text = leaf.text
        if (text == "nameof" || r.syntax.symbolAt(leaf) != null) return null
        val arguments = call.argumentList?.arguments ?: return null
        if (arguments.any { it.nameColon != null || it.expression == null || it.expression?.text == "__arglist" }) return null
        val written = (callee as? CSharpGenericName)?.typeArgumentList?.arguments?.map { r.resolveType(it) ?: return null }
        val groups = groups(callee, text, written?.size) ?: return null
        val only = { groups.all()?.flatMap { it.candidates }?.singleOrNull() }
        val quiet = Analysis(callee, arguments, only)
        var failed: Pair<Group, List<Outcome>>? = null
        var receiver: SemanticType? = null
        var index = 0
        while (true) {
            // the extension methods are looked up only when no method of the type applies
            val group = if (index == 0) groups.members else groups.extensions?.value?.getOrNull(index - 1) ?: if (groups.extensions != null && groups.extensions.value == null) return quiet else break
            index++
            if (group.candidates.isEmpty()) continue
            if (!lambdasAgree(group, arguments)) return quiet
            if (group.candidates.any { it.reduced } && receiver == null) receiver = r.expressions.receiver(callee) ?: return quiet
            val symbols = group.candidates.map { it.symbol }
            val outcomes = group.candidates.map { c ->
                overloads.evaluate(c.symbol, c.reduced, receiver, arguments, symbols, written) { targets, self ->
                    inference.infer(c.symbol, targets, arguments, self?.let { (type, parameter) -> type to parameter }, call, callee)
                }
            }
            if (outcomes.any { it is Outcome.Unknown }) return quiet
            val applicable = group.candidates.indices.filter { outcomes[it] is Outcome.Applicable }
            if (applicable.isEmpty()) {
                if (failed == null) failed = group to outcomes
                continue
            }
            // §12.6.4.1: the methods of a base type drop out when a method of a more derived type applies
            val nearest = applicable.minOf { group.candidates[it].depth }
            val kept = applicable.filter { group.candidates[it].depth == nearest }
            val forms = kept.map { (outcomes[it] as Outcome.Applicable).form }
            val best = overloads.unbeaten(forms, arguments, receiver) ?: return quiet
            if (best.size < 2 || !group.ordered) return quiet
            val first = kept.filter { forms[kept.indexOf(it)] in best }.take(2).map { group.candidates[it] }
            return Analysis(callee, arguments, only, ambiguous = first[0] to first[1])
        }
        val (group, outcomes) = failed ?: return quiet
        if (outcomes.any { it is Outcome.ConstraintFailed }) return quiet
        val bad = group.candidates.indices.filter { outcomes[it] is Outcome.Bad }
        val pool = bad.ifEmpty { group.candidates.indices.filter { outcomes[it] is Outcome.InferenceFailed } }
        if (pool.isEmpty() || pool.size > 1 && !group.ordered) return quiet
        return Analysis(callee, arguments, only, reported = group.candidates[pool.first()], outcome = outcomes[pool.first()])
    }

    /**
     * Whether the lambdas among [arguments] whose bodies use their implicitly typed parameters get the same parameter types from every
     * candidate of [group] that may take them: the body is bound once (by the resolver), and whether it binds may differ by those types
     * (`x => x.Length` binds for a `string` and not for an `int`) — which decides applicability, so it is not judged then.
     */
    private fun lambdasAgree(group: Group, arguments: List<CSharpArgument>): Boolean {
        for ((i, argument) in arguments.withIndex()) {
            val lambda = argument.expression?.let(overloads::unparenthesized) as? CSharpAnonymousFunctionExpression ?: continue
            val parameters = parametersOf(lambda) ?: continue
            val names = parameters.filter { it.type == null }.mapNotNull { it.identifier?.text }.toSet()
            if (names.isEmpty()) continue
            val body: PsiElement = lambda.expressionBody ?: lambda.block ?: return false
            if (PsiTreeUtil.findChildrenOfType(body, CSharpIdentifierName::class.java).none { it.identifier?.text in names } &&
                !(body is CSharpIdentifierName && body.identifier?.text in names)) continue
            var first: List<SemanticType?>? = null
            for (c in group.candidates) {
                val signature = r.signature(c.symbol, c.reduced) ?: return false
                val parameter = signature.getOrNull(i) ?: signature.lastOrNull()?.takeIf { it.isParams } ?: continue
                if (parameter.isParams) return false
                val type = parameter.type() ?: return false
                if (type is SemanticType.Parameter) return false
                val delegate = r.expressions.unwrapExpression(type)?.let(r.expressions::delegateSignature) ?: continue
                if (delegate.first.size != parameters.size) continue
                if (delegate.first.any { it == null || mentionsTypeParameter(it) }) return false
                val known = first
                if (known == null) first = delegate.first else if (known.zip(delegate.first).any { (a, b) -> overloads.same(a, b) != true }) return false
            }
        }
        return true
    }

    /** The groups of candidates of a call, in the order overload resolution tries them: the methods of the type, then each scope of extension methods. */
    private fun groups(callee: CSharpSimpleName, text: String, arity: Int?): Groups? {
        val parent = callee.parent
        if (parent is CSharpMemberBindingExpression) return null
        val members = checks.overloads(callee, text, arity, extensions = false) ?: return null
        if (members.any(::hasPriority)) return null
        val type: SemanticType
        val keep: (Boolean) -> Boolean
        var extensions = false
        if (parent is CSharpMemberAccessExpression) {
            if (parent.nameElement != callee) return null
            when (val q = parent.expression?.let(r::qualifier)) {
                // C# 7.3: through a type only the static methods, through a value only the instance ones
                is CSharpNameResolver.Qualifier.Type -> { type = q.type; keep = { it } }
                is CSharpNameResolver.Qualifier.Value -> { type = q.type; keep = { !it }; extensions = true }
                else -> return null
            }
        } else {
            val owner = r.syntax.enclosingTypes(callee).firstOrNull { checks.has(r.selfType(it), text) } ?: return null
            type = r.selfType(owner)
            val context = instanceContext(callee)
            if (context == null && members.any { overloads.isStatic(it) != true }) return null
            keep = if (context == false) { static -> static } else { _ -> true }
        }
        // a record gets members the compiler writes (`Equals(R?)`, `Deconstruct`, `PrintMembers`): they are not among the declared ones
        if (text in RECORD_GENERATED && recordInChain(type)) return null
        val kept = members.filter { keep(overloads.isStatic(it) ?: return null) }
        if (kept.size != members.size && kept.isEmpty()) return null
        val group = memberGroup(type, kept) ?: return null
        return Groups(group, if (extensions) lazy(LazyThreadSafetyMode.NONE) { extensionGroups(callee, text, arity, type) } else null)
    }

    private fun recordInChain(type: SemanticType, depth: Int = 0): Boolean = depth < 16 && type is SemanticType.Source &&
        (type.info.kind == TypeKind.RECORD || type.info.kind == TypeKind.RECORD_STRUCT || r.baseTypes(type).any { recordInChain(it, depth + 1) })

    /** Whether a simple name at [callee] is in an instance context (true), a static one (false), or one this does not tell (field initializers). */
    private fun instanceContext(callee: CSharpSimpleName): Boolean? {
        val member = PsiTreeUtil.getParentOfType(callee, CSharpMemberDeclaration::class.java) ?: return null
        if (member !is CSharpMethodDeclaration && member !is CSharpConstructorDeclaration && member !is CSharpBasePropertyDeclaration) return null
        if (member.modifiers.any { it.text == "static" }) return false
        // a static lambda or local function cannot reach `this`
        var at: PsiElement? = callee.parent
        while (at != null && at != member) {
            if ((at is CSharpAnonymousFunctionExpression && at.modifiers.any { it.text == "static" }) ||
                (at is CSharpLocalFunctionStatement && at.modifiers.any { it.text == "static" })) return false
            at = at.parent
        }
        return true
    }

    /**
     * The methods of [type] as one group: in Roslyn's order (the most derived type's first, each type's in declaration or metadata order),
     * an override in its own place but standing for the method it overrides, which leaves the group.
     */
    private fun memberGroup(type: SemanticType, members: List<CSharpSymbol>): Group? {
        val chain = classChain(type) ?: return null
        class Entry(val symbol: CSharpSymbol, val depth: Int, val order: Int)
        val entries = members.map { symbol ->
            val key = declaringKey(symbol) ?: return null
            val depth = chain.indexOf(key).takeIf { it >= 0 } ?: return null
            val order = when (symbol) {
                is CSharpSymbol.SourceMember -> symbol.element.textOffset
                is CSharpSymbol.LibraryMember -> symbol.member.row
                else -> return null
            }
            Entry(symbol, depth, order)
        }.sortedWith(compareBy<Entry> { it.depth }.thenBy { it.order })
        val removed = HashSet<CSharpSymbol>()
        val result = ArrayList<Candidate>()
        for (entry in entries) {
            if (entry.symbol in removed) continue
            val method = (entry.symbol as? CSharpSymbol.SourceMember)?.element as? CSharpMethodDeclaration
            if (method != null && method.modifiers.any { it.text == "override" }) {
                val base = entries.firstOrNull { it.depth > entry.depth && it.symbol !in removed && sameSignature(it.symbol, entry.symbol) } ?: return null
                removed += base.symbol
                result += Candidate(entry.symbol, base.symbol, base.depth, false)
            } else {
                result += Candidate(entry.symbol, entry.symbol, entry.depth, false)
            }
        }
        // a type of the solution in several files (partial ones are left out before): no order known between them
        val ordered = entries.groupBy { it.depth }.values.all { e -> e.mapNotNull { (it.symbol as? CSharpSymbol.SourceMember)?.element?.containingFile }.distinct().size <= 1 }
        return Group(result, ordered)
    }

    private fun sameSignature(a: CSharpSymbol, b: CSharpSymbol): Boolean {
        if (r.isGeneric(a) || r.isGeneric(b)) return false
        val x = r.signature(a, false) ?: return false
        val y = r.signature(b, false) ?: return false
        return x.size == y.size && x.zip(y).all { (p, q) -> overloads.same(p.type(), q.type()) == true }
    }

    /** `S:key` / `L:full name` of [type] and its base classes, nearest first; null when not all known. */
    private fun classChain(type: SemanticType): List<String>? {
        val chain = ArrayList<String>()
        var at: SemanticType? = type
        while (at != null && chain.size < 32) {
            when (at) {
                is SemanticType.Source -> {
                    chain += "S:" + at.info.key
                    if (at.info.kind == TypeKind.INTERFACE) return chain
                    at = r.baseTypes(at).firstOrNull { !overloads.isInterface(it) }
                }
                is SemanticType.Library -> {
                    chain += "L:" + at.type.fullName
                    if (at.type.kind != IndexedTypeKind.INTERFACE) for (base in r.session.baseTypes(r.assemblies, at.type)) chain += "L:" + base.type.fullName
                    return chain
                }
                else -> return null
            }
        }
        return chain
    }

    private fun declaringKey(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> (symbol.owner as? SemanticType.Source)?.let { "S:" + it.info.key }
        is CSharpSymbol.LibraryMember -> "L:" + symbol.member.type.fullName
        else -> null
    }

    private fun hasPriority(symbol: CSharpSymbol): Boolean =
        symbol is CSharpSymbol.LibraryMember && symbol.member.attributes.any { it == "System.Runtime.CompilerServices.OverloadResolutionPriorityAttribute" }

    /**
     * The extension methods named [text] a receiver of [receiver] can call at [site], by scope (§12.8.10.3): innermost namespace first, each
     * with the static classes of that namespace and of what its `using`s import. Null when not all is known (imports that do not resolve).
     */
    private fun extensionGroups(site: CSharpSimpleName, text: String, arity: Int?, receiver: SemanticType): List<Group>? {
        if (!r.importsKnown(site)) return null
        val scopes = r.extensionScopes(site)
        fun level(namespace: String) = scopes.indexOfFirst { it.first == namespace || namespace in it.second }
        val found = java.util.TreeMap<Int, MutableList<CSharpSymbol>>()
        for (member in r.assemblies.membersNamed(text)) {
            if (member.kind != IndexedMemberKind.EXTENSION_METHOD || member.isHidden || arity != null && member.arity != arity) continue
            val at = level(member.type.namespace).takeIf { it >= 0 } ?: continue
            val symbol = CSharpSymbol.LibraryMember(member)
            if (hasPriority(symbol)) return null
            when (reducible(symbol, receiver)) {
                false -> continue
                null -> return null
                true -> found.getOrPut(at) { ArrayList() } += symbol
            }
        }
        val module = ModuleUtilCore.findModuleForPsiElement(site)
        for (element in r.session.sourceExtensions(text)) {
            val method = element as? CSharpMethodDeclaration ?: return null
            if (arity != null && (method.typeParameterList?.parameters?.size ?: 0) != arity) continue
            val namespace = r.namespaceOfMethod(method) ?: return null
            val at = level(namespace).takeIf { it >= 0 } ?: continue
            val owner = method.parent as? CSharpTypeDeclaration ?: return null
            // an extension method that is not public, of another project: whether it is visible is not modeled
            val public = method.modifiers.any { it.text == "public" } && owner.modifiers.any { it.text == "public" }
            if (!public && ModuleUtilCore.findModuleForPsiElement(method) != module) return null
            val symbol = CSharpSymbol.SourceMember(method, Member.method(listOf("static"), true).at { method }, null)
            when (reducible(symbol, receiver)) {
                false -> continue
                null -> return null
                true -> found.getOrPut(at) { ArrayList() } += symbol
            }
        }
        return found.values.map { symbols ->
            val sources = symbols.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
            val libraries = symbols.mapNotNull { (it as? CSharpSymbol.LibraryMember)?.member }
            // Roslyn's order of the candidates of a scope: known within a file of the solution, or within one static class of an assembly
            val ordered = symbols.size == 1 || libraries.isEmpty() && sources.map { it.containingFile }.distinct().size == 1 ||
                sources.isEmpty() && libraries.map { it.type }.distinct().size == 1
            val sorted = if (libraries.isEmpty()) symbols.sortedBy { (it as CSharpSymbol.SourceMember).element.textOffset }
            else if (sources.isEmpty()) symbols.sortedBy { (it as CSharpSymbol.LibraryMember).member.row } else symbols
            Group(sorted.map { Candidate(it, it, 0, true) }, ordered)
        }
    }

    /** Whether [receiver] can be the `this` argument of the extension method [symbol]: identity, reference or boxing conversions only. */
    private fun reducible(symbol: CSharpSymbol, receiver: SemanticType): Boolean? {
        val self = r.signature(symbol, false)?.firstOrNull() ?: return null
        if (self.byRef) return null
        if (symbol is CSharpSymbol.SourceMember && (symbol.element as? CSharpMethodDeclaration)?.parameterList?.parameters?.firstOrNull()?.modifiers?.any { it.text != "this" } != false) return null
        if (symbol is CSharpSymbol.LibraryMember && r.session.parameters(symbol.member).firstOrNull()?.isByReference != false) return null
        val type = self.type() ?: return null
        // a generic `this` parameter: the receiver is checked again once the type arguments are inferred
        if (mentionsTypeParameter(type)) return r.expressions.receiverFits(symbol, receiver) && !surelyNoInstance(type, receiver)
        return when (overloads.classify(receiver, type, userDefined = false)) {
            CSharpNameResolver.Conversion.IDENTITY -> true
            CSharpNameResolver.Conversion.IMPLICIT -> !(overloads.isReference(receiver) == false && overloads.isReference(type) == false)
            CSharpNameResolver.Conversion.NONE -> false
            CSharpNameResolver.Conversion.UNKNOWN -> null
        }
    }

    /**
     * `this IQueryable<T>` (or `IAsyncEnumerable<T>`, `ParallelQuery<T>`) for a `List<int>`: a receiver of a known type that is no instance
     * of the generic type at all leaves nothing to infer from, and Roslyn drops the method before overload resolution (it is no candidate,
     * so `numbers.Take("three")` reports Enumerable's). Spans and `Nullable<T>` aside: C# 14 converts arrays and strings to spans.
     */
    private fun surelyNoInstance(type: SemanticType, receiver: SemanticType): Boolean {
        val generic = type as? SemanticType.Library ?: return false
        if (generic.arguments.isEmpty() || generic.type.fullName in BY_CONVERSION) return false
        if (receiver !is SemanticType.Library && receiver !is SemanticType.Source && receiver !is SemanticType.ArrayOf) return false
        if (!checks.isKnown(receiver) || receiver is SemanticType.Source && checks.isPartial(receiver.info)) return false
        return r.expressions.instanceOf(receiver, generic.type.fullName) == null
    }

    // ---- calls: CS0121, CS0411, CS1503

    private fun checkCall(call: CSharpInvocationExpression) {
        val analysis = analysis(call) ?: return
        val at = analysis.callee.identifier?.textRange ?: return
        analysis.ambiguous?.let { (x, y) ->
            val first = qualifiedDisplay(x.shown) ?: return
            val second = qualifiedDisplay(y.shown) ?: return
            return checks.report("CS0121", "The call is ambiguous between the following methods or properties: '$first' and '$second'", at)
        }
        val reported = analysis.reported ?: return
        when (val outcome = analysis.outcome) {
            is Outcome.InferenceFailed -> {
                val shown = genericDisplay(reported.symbol) ?: return
                checks.report("CS0411", "The type arguments for method '$shown' cannot be inferred from the usage. Try specifying the type arguments explicitly.", at)
            }
            is Outcome.Bad -> reportBadArguments(analysis.arguments, reported, outcome)
            else -> {}
        }
    }

    /** CS1503 for each argument of [outcome] that does not convert; lambdas say their own errors ([checkLambda]). */
    private fun reportBadArguments(arguments: List<CSharpArgument>, candidate: Candidate, outcome: Outcome.Bad) {
        if (outcome.refMismatch) return
        val offset = if (candidate.reduced) 2 else 1
        for (i in outcome.bad) {
            val expression = arguments[i].expression ?: return
            val value = overloads.unparenthesized(expression)
            if (value is CSharpAnonymousFunctionExpression || inference.isMethodGroup(value)) continue
            if (value is CSharpInterpolatedStringExpression || value is CSharpCollectionExpression || value is CSharpTupleExpression ||
                value is CSharpImplicitObjectCreationExpression || value.elementType == SyntaxKind.DefaultLiteralExpression) continue
            val from = if (value.elementType == SyntaxKind.NullLiteralExpression) "<null>" else r.typeOf(value)?.let { CSharpTypeDisplay.display(it) } ?: continue
            val to = CSharpTypeDisplay.display(outcome.form.targets[i] ?: continue) ?: continue
            checks.report("CS1503", "Argument ${i + offset}: cannot convert from '$from' to '$to'", expression.textRange)
        }
    }

    private fun mentionsTypeParameter(type: SemanticType?): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> true
        is SemanticType.ArrayOf -> mentionsTypeParameter(type.element)
        is SemanticType.Library -> type.arguments.any(::mentionsTypeParameter)
        is SemanticType.Source -> type.arguments.any(::mentionsTypeParameter)
    }

    // ---- how Roslyn names a method

    /** `Probe.Amb.Inner.M<T>(T, ref int)`: as CS0121 names a method, the namespaces and types fully written. */
    private fun qualifiedDisplay(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> {
            val method = symbol.element as? CSharpMethodDeclaration
            val owner = method?.let { PsiTreeUtil.getParentOfType(it, CSharpBaseTypeDeclaration::class.java) }
            val typeName = owner?.let(checks::declaringName)
            if (method == null || owner == null || typeName == null) null
            else {
                val namespaces = generateSequence(owner.parent) { it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>().toList().asReversed()
                val prefix = namespaces.map { it.nameElement?.text?.filterNot(Char::isWhitespace) ?: return null }.joinToString("") { "$it." }
                sourceDisplay(method, "$prefix$typeName", qualified = true, owner = null)
            }
        }
        // the index keeps no nullable annotations: `string` or `string?` cannot be told, so only parameters of value types and type parameters
        is CSharpSymbol.LibraryMember -> libraryDisplay(symbol, qualified = true, references = false)
        else -> null
    }

    /** `Factory.Create<T>()`, `Outer<int>.Nested.Make<T>()`, `Enumerable.Select<TSource, TResult>(IEnumerable<TSource>, …)`: as CS0411 names a method. */
    private fun genericDisplay(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> {
            val method = symbol.element as? CSharpMethodDeclaration
            val declaration = method?.let { PsiTreeUtil.getParentOfType(it, CSharpBaseTypeDeclaration::class.java) }
            val owner = symbol.owner as? SemanticType.Source
            val typeName = when {
                declaration == null -> null
                owner != null && owner.arguments.isEmpty() && owner.outer?.let { it.arguments.isEmpty() } != false -> checks.declaringName(declaration)
                owner != null -> CSharpTypeDisplay.display(owner, qualified = false)
                else -> checks.declaringName(declaration)
            }
            if (method == null || typeName == null) null else sourceDisplay(method, typeName, qualified = false, owner = owner)
        }
        // the parameters of reference types: `?` where the default is `null`, the annotations of the rest are not in the index
        is CSharpSymbol.LibraryMember -> libraryDisplay(symbol, qualified = false, references = true)
        else -> null
    }

    private fun sourceDisplay(method: CSharpMethodDeclaration, typeName: String, qualified: Boolean, owner: SemanticType.Source?): String? {
        val resolver = (method.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
        val typeParameters = method.typeParameterList?.parameters?.map { it.identifier?.text ?: return null }?.joinToString(", ", "<", ">").orEmpty()
        val parameters = method.parameterList?.parameters.orEmpty().map { p ->
            val modifiers = p.modifiers.map { it.text }.filter { it != "this" }
            if (modifiers.any { it != "params" && it != "ref" && it != "out" && it != "in" }) return null
            val syntax = p.type ?: return null
            var type = resolver.resolveType(syntax) ?: return null
            if (owner != null) type = r.substitute(type, owner) ?: return null
            var shown = CSharpTypeDisplay.display(type, qualified) ?: return null
            // `string?`, `T?`: a nullable reference type is written as such; one nested deeper (`List<string?>`) is not modeled
            val nullable = syntax is CSharpNullableType
            if (syntax.text.dropLast(if (nullable) 1 else 0).contains('?')) return null
            if (nullable && !r.isNullable(type)) shown += "?"
            (modifiers + shown).joinToString(" ")
        }
        return "$typeName.${method.identifier?.text ?: return null}$typeParameters(${parameters.joinToString(", ")})"
    }

    private fun libraryDisplay(symbol: CSharpSymbol.LibraryMember, qualified: Boolean, references: Boolean): String? {
        val member = symbol.member
        val ownerType = SemanticType.Library(member.type, symbol.declaringArguments)
        val owner = CSharpTypeDisplay.display(ownerType, qualified) ?: return null
        val methodParameters = member.typeParameters.mapIndexed { i, t -> SemanticType.Parameter(t.name, null, i, true) }
        val typeParameters = if (member.arity > 0) member.typeParameters.joinToString(", ", "<", ">") { it.name } else ""
        val parameters = r.session.parameters(member).map { p ->
            val type = r.fromRef(p.typeRef, symbol.declaringArguments, methodParameters) ?: return null
            var shown = CSharpTypeDisplay.display(type, qualified) ?: return null
            if (type !is SemanticType.Parameter && overloads.isReference(type) != false) {
                if (!references && !(p.hasDefault && p.defaultValue == "null")) return null
                if (p.hasDefault && p.defaultValue == "null") shown += "?"
            }
            listOfNotNull("params".takeIf { p.isParams }, "out".takeIf { p.isOut }, "ref".takeIf { p.isRef && !p.isOut }, "in".takeIf { p.isIn && !p.isOut }, shown).joinToString(" ")
        }
        return "$owner.${member.name}$typeParameters(${parameters.joinToString(", ")})"
    }

    // ---- lambdas: CS1593, CS1660, CS1661, CS1678, CS1676, CS1677, CS1643

    private fun checkLambda(lambda: CSharpAnonymousFunctionExpression) {
        if ((lambda as? CSharpParenthesizedLambdaExpression)?.returnType != null || (lambda as? CSharpLambdaExpression)?.attributeLists?.isNotEmpty() == true) return
        val arrow = (lambda as? CSharpLambdaExpression)?.arrowToken ?: (lambda as? CSharpAnonymousMethodExpression)?.delegateKeyword ?: return
        val kind = if (lambda is CSharpAnonymousMethodExpression) "anonymous method" else "lambda expression"
        val (target, place) = target(lambda) ?: return checkInferredPaths(lambda, arrow)
        // an event takes the lambda by `+=` / `-=`: Roslyn reports the conversion at the whole assignment
        val at: TextRange = place ?: arrow.textRange
        if (r.definitionName(target) == EXPRESSION || mentionsTypeParameter(target)) return
        val delegate = r.expressions.delegateSignature(target)
        if (delegate == null) {
            if (!notDelegate(target)) return
            val shown = CSharpTypeDisplay.display(target, qualified = false) ?: return
            return checks.report("CS1660", "Cannot convert $kind to type '$shown' because it is not a delegate type", at)
        }
        val shown = CSharpTypeDisplay.display(target, qualified = false) ?: return
        val written = parametersOf(lambda)
        if (written != null && written.size != delegate.first.size) {
            return checks.report("CS1593", "Delegate '$shown' does not take ${written.size} arguments", at)
        }
        val kinds = overloads.delegateRefKinds(target) ?: return
        if (written == null) {
            // `delegate { … }` takes any parameters, but no `out` ones
            if (kinds.any { it == "out" }) return
        } else if (!checkRefKinds(written, kinds)) return
        if (written != null && written.isNotEmpty() && written.all { it.type != null }) {
            if (!checkParameterTypes(written, delegate.first, kinds, kind, shown, at)) return
        }
        if (place == null) checkPaths(lambda, delegate.second, kind, shown, arrow)
    }

    /** CS1676 / CS1677 for each parameter whose `ref` kind is not the delegate's; false when something said or not known. */
    private fun checkRefKinds(written: List<CSharpParameter>, kinds: List<String>): Boolean {
        val wrong = ArrayList<Triple<Int, String, String>>()
        for ((i, p) in written.withIndex()) {
            val own = overloads.lambdaRefKind(p) ?: return false
            val wanted = kinds.getOrNull(i) ?: return false
            if (own == wanted) continue
            // `in` for a `ref` parameter is allowed (C# 12, a warning at most)
            if (own == "in" && wanted == "ref") return false
            wrong += Triple(i, own, wanted)
        }
        for ((i, own, wanted) in wrong) {
            val name = written[i].identifier ?: continue
            if (wanted.isEmpty()) checks.report("CS1677", "Parameter ${i + 1} should not be declared with the '$own' keyword", name.textRange)
            else checks.report("CS1676", "Parameter ${i + 1} must be declared with the '$wanted' keyword", name.textRange)
        }
        return wrong.isEmpty()
    }

    /** The parameters a lambda writes; null for `delegate { … }`, which takes any. */
    private fun parametersOf(lambda: CSharpAnonymousFunctionExpression): List<CSharpParameter>? = when (lambda) {
        is CSharpSimpleLambdaExpression -> listOfNotNull(lambda.parameter)
        is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty()
        is CSharpAnonymousMethodExpression -> lambda.parameterList?.parameters
        else -> null
    }

    /** CS1661 and a CS1678 for each parameter whose written type is not the delegate's; false when something said or not known. */
    private fun checkParameterTypes(written: List<CSharpParameter>, expected: List<SemanticType?>, kinds: List<String>, kind: String, shown: String, at: TextRange): Boolean {
        val wrong = ArrayList<Triple<Int, SemanticType, SemanticType>>()
        for ((i, p) in written.withIndex()) {
            val syntax = p.type ?: return false
            // `dynamic` is `object`, `nint` is `IntPtr`, a tuple has its names: identities the types here may not see
            if (syntax.text.contains("dynamic") || syntax.text.contains("nint") || syntax.text.contains("nuint") || syntax.text.contains("(")) return false
            val type = r.resolveType(syntax) ?: return false
            val wanted = expected.getOrNull(i) ?: return false
            when (overloads.same(type, wanted)) {
                true -> {}
                false -> wrong += Triple(i, type, wanted)
                null -> return false
            }
        }
        if (wrong.isEmpty()) return true
        val messages = wrong.map { (i, type, wanted) ->
            val prefix = kinds.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
            val from = CSharpTypeDisplay.display(type) ?: return false
            val to = CSharpTypeDisplay.display(wanted) ?: return false
            Triple(i, prefix + from, prefix + to)
        }
        checks.report("CS1661", "Cannot convert $kind to type '$shown' because the parameter types do not match the delegate parameter types", at)
        for ((i, from, to) in messages) {
            val name = written[i].identifier ?: continue
            checks.report("CS1678", "Parameter ${i + 1} is declared as type '$from' but should be '$to'", name.textRange)
        }
        return false
    }

    /** CS1643: a block body whose end is reachable, for a delegate that returns a value (`Task<T>` of an `async` one). */
    private fun checkPaths(lambda: CSharpAnonymousFunctionExpression, returns: SemanticType?, kind: String, shown: String, at: PsiElement) {
        val block = lambda.block ?: return
        val result = returns ?: return
        val name = r.definitionName(result) ?: return
        if (name == VOID) return
        if (r.expressions.isAsync(lambda.modifiers) && name != TASK && name != VALUE_TASK) return
        if (PsiTreeUtil.findChildOfAnyType(block, CSharpGotoStatement::class.java, CSharpLabeledStatement::class.java) != null) return
        if (CSharpReachability(r).endOf(block) != CSharpReachability.Reach.YES) return
        checks.report("CS1643", "Not all code paths return a value in $kind of type '$shown'", at.textRange)
    }

    /**
     * CS1643 for a lambda given to a call whose delegate is inferred (`numbers.Select(x => { … })`): the call resolves to one method whose
     * parameter is no `Expression<…>`, and the delegate, its type arguments inferred, is closed and takes as many parameters as the lambda.
     */
    private fun checkInferredPaths(lambda: CSharpAnonymousFunctionExpression, at: PsiElement) {
        if (lambda.block == null || lambda is CSharpAnonymousMethodExpression) return
        val argument = argumentOf(lambda) ?: return
        val call = (argument.parent as? CSharpArgumentList)?.parent as? CSharpInvocationExpression ?: return
        val callee = calleeOf(call) ?: return
        val symbol = r.resolveName(callee)?.symbols?.singleOrNull()?.takeIf(r::isMethod) ?: return
        val arguments = call.argumentList?.arguments ?: return
        if (arguments.any { it.nameColon != null }) return
        val declared = r.expressions.parameterTypesAt(call, listOf(symbol), arguments.indexOf(argument)).singleOrNull() ?: return
        if (r.definitionName(declared) == EXPRESSION) return
        val target = r.expressions.delegateOf(lambda) ?: return
        if (mentionsTypeParameter(target) || !overloads.closed(target)) return
        val delegate = r.expressions.delegateSignature(target) ?: return
        if (delegate.first.size != parametersOf(lambda)?.size) return
        val shown = CSharpTypeDisplay.display(target, qualified = false) ?: return
        checkPaths(lambda, delegate.second, "lambda expression", shown, at)
    }

    private fun argumentOf(lambda: CSharpExpression): CSharpArgument? {
        var at: PsiElement = lambda
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        return at.parent as? CSharpArgument
    }

    private fun calleeOf(call: CSharpInvocationExpression): CSharpSimpleName? = when (val e = call.expression) {
        is CSharpIdentifierName, is CSharpGenericName -> e as CSharpSimpleName
        is CSharpMemberAccessExpression -> e.nameElement?.takeIf { it is CSharpIdentifierName || it is CSharpGenericName }
        else -> null
    }

    /**
     * The type a lambda converts to where its place says it, and where Roslyn reports the conversion (null: at the lambda): a variable
     * or property declared with a type, the left side of `=`, `+=` / `-=` of an event or a delegate, a cast, a `return` or `=>` of a
     * function with a written return type, the parameter of the candidate a call reports or the one candidate it has.
     */
    private fun target(lambda: CSharpAnonymousFunctionExpression): Pair<SemanticType, TextRange?>? {
        var at: PsiElement = lambda
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val type = when (val parent = at.parent) {
            is CSharpEqualsValueClause -> when (val owner = parent.parent) {
                is CSharpVariableDeclarator -> {
                    val declaration = owner.parent as? CSharpVariableDeclaration ?: return null
                    if (owner.argumentList != null || declaration.parent is CSharpEventFieldDeclaration) return null
                    declaration.type?.takeIf { !r.isVar(it) }?.let(r::resolveType)
                }
                is CSharpPropertyDeclaration -> owner.type?.let(r::resolveType)
                else -> null
            }
            is CSharpAssignmentExpression -> {
                if (parent.right != at) return null
                val left = parent.left ?: return null
                if (left !is CSharpIdentifierName && left !is CSharpMemberAccessExpression) return null
                when (parent.operatorToken?.text) {
                    "=" -> r.typeOf(left)
                    "+=", "-=" -> {
                        val type = r.typeOf(left)?.takeIf { r.expressions.delegateSignature(it) != null } ?: return null
                        val name = (left as? CSharpMemberAccessExpression)?.nameElement ?: left as CSharpSimpleName
                        val symbol = name.identifier?.let(r::resolve)?.single ?: return null
                        return if (isEvent(symbol)) type to parent.textRange else type to null
                    }
                    else -> null
                }
            }
            is CSharpCastExpression -> parent.type?.let(r::resolveType)
            is CSharpArgument -> parameterTarget(parent)
            is CSharpReturnStatement -> returnTarget(parent)
            is CSharpArrowExpressionClause -> returnTarget(parent)
            else -> null
        } ?: return null
        return type to null
    }

    private fun isEvent(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> symbol.element is CSharpEventDeclaration || symbol.element.parent?.parent is CSharpEventFieldDeclaration ||
            symbol.element is CSharpEventFieldDeclaration
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.EVENT
        else -> false
    }

    /** What a `return` / `=>` returns where the function around it writes it: a method, a local function, a property, an indexer, a `get`. */
    private fun returnTarget(at: PsiElement): SemanticType? {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpAnonymousFunctionExpression -> return null
                is CSharpLocalFunctionStatement -> return declaredReturn(current.returnType, current.modifiers, current)
                is CSharpMethodDeclaration -> return declaredReturn(current.returnType, current.modifiers, current)
                is CSharpAccessorDeclaration -> {
                    if (current.keyword?.text != "get") return null
                    val property = PsiTreeUtil.getParentOfType(current, CSharpBasePropertyDeclaration::class.java) ?: return null
                    if (property is CSharpEventDeclaration) return null
                    return property.type?.let(r::resolveType)
                }
                is CSharpBasePropertyDeclaration -> return if (current is CSharpEventDeclaration) null else current.type?.let(r::resolveType)
                is CSharpMemberDeclaration -> return null
            }
            current = current.parent
        }
        return null
    }

    private fun declaredReturn(type: CSharpType?, modifiers: List<PsiElement>, function: PsiElement): SemanticType? {
        val declared = type?.let(r::resolveType) ?: return null
        // an iterator returns by `yield`
        if (PsiTreeUtil.findChildOfType(function, CSharpYieldStatement::class.java) != null) return null
        if (!r.expressions.isAsync(modifiers)) return declared
        return (declared as? SemanticType.Library)?.takeIf { it.type.fullName == TASK || it.type.fullName == VALUE_TASK }?.arguments?.firstOrNull()
    }

    /**
     * The parameter a lambda argument goes to: in the candidate a call with no applicable one reports, when the lambda is what does not
     * convert; or in the one candidate of the call — a method of the solution or an assembly, not generic — by its position.
     */
    private fun parameterTarget(argument: CSharpArgument): SemanticType? {
        if (argument.nameColon != null || argument.refKindKeyword != null) return null
        val call = (argument.parent as? CSharpArgumentList)?.parent as? CSharpInvocationExpression ?: return null
        val analysis = analysis(call) ?: return null
        val index = analysis.arguments.indexOf(argument)
        val outcome = analysis.outcome
        if (outcome is Outcome.Bad) return if (index in outcome.bad && !outcome.refMismatch) outcome.form.targets[index] else null
        if (analysis.reported != null || analysis.ambiguous != null) return null
        val single = analysis.single ?: return null
        if (r.isGeneric(single.symbol)) return null
        val parameters = r.signature(single.symbol, single.reduced) ?: return null
        if (!r.fits(parameters, analysis.arguments)) return null
        val parameter = parameters.getOrNull(index)?.takeIf { !it.isParams && !it.byRef } ?: return null
        return parameter.type()
    }

    /** A type no lambda converts to: a struct, an enum, `string`, a class of the solution; not `object`, `Delegate`, an interface. */
    private fun notDelegate(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> {
            val t = type.type
            val inner = type.arguments.singleOrNull() as? SemanticType.Library
            (t.kind == IndexedTypeKind.STRUCT || t.kind == IndexedTypeKind.ENUM || t.fullName == "System.String") && !takesDelegate(type) &&
                (t.fullName != NULLABLE || inner != null && (inner.type.kind == IndexedTypeKind.STRUCT || inner.type.kind == IndexedTypeKind.ENUM) && !takesDelegate(inner))
        }
        is SemanticType.Source -> type.info.kind in listOf(TypeKind.CLASS, TypeKind.STRUCT, TypeKind.RECORD, TypeKind.RECORD_STRUCT, TypeKind.ENUM) &&
            !checks.isPartial(type.info) && checks.isKnown(type) &&
            type.info.parts.all { part ->
                when (val declaration = part.element()) {
                    is CSharpTypeDeclaration -> declaration.members.none { it is CSharpConversionOperatorDeclaration }
                    else -> declaration is CSharpEnumDeclaration
                }
            }
        else -> false
    }

    /** Whether an implicit operator of [type] may take a delegate (`string`'s to `ReadOnlySpan<char>` does not, `Nullable<T>`'s takes a `T`). */
    private fun takesDelegate(type: SemanticType.Library): Boolean = type.type.members.filter { it.name == "op_Implicit" }.any { op ->
        val operand = r.signature(CSharpSymbol.LibraryMember(op, type.arguments), false)?.firstOrNull()?.type?.invoke() ?: return@any true
        operand !is SemanticType.Library || operand.type.kind == IndexedTypeKind.DELEGATE || operand.type.kind == IndexedTypeKind.INTERFACE ||
            operand.type.fullName in NATURAL_TARGETS
    }

    companion object {
        private const val EXPRESSION = "System.Linq.Expressions.Expression`1"
        private const val NULLABLE = "System.Nullable`1"
        private const val VOID = "System.Void"
        private const val TASK = "System.Threading.Tasks.Task`1"
        private const val VALUE_TASK = "System.Threading.Tasks.ValueTask`1"
        /** `this` parameter types a receiver reaches by a conversion rather than as an instance (spans of C# 14, nullable value types). */
        private val BY_CONVERSION = setOf("System.Span`1", "System.ReadOnlySpan`1", NULLABLE)
        /** Methods the compiler writes into a record, which its declarations do not list. */
        private val RECORD_GENERATED = setOf("Equals", "GetHashCode", "ToString", "Deconstruct", "PrintMembers", "<Clone>$")
        private val NATURAL_TARGETS =setOf("System.Object", "System.Delegate", "System.MulticastDelegate", "System.Linq.Expressions.Expression", "System.Linq.Expressions.LambdaExpression")
    }
}
