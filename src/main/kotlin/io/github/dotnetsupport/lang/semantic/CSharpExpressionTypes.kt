package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpTypePositions

/**
 * The types of expressions (layer 11b of CSHARP_PSI_MIGRATION.md, task C2) for a [CSharpNameResolver]: the natural type Roslyn's
 * `TypeInfo.Type` gives, as far as the declarations, the index of the assemblies and a few rules of the language tell it — literals, names,
 * member access, calls (the return type of the resolved method, its type arguments inferred from the arguments, lambdas included),
 * `new`, element access, casts, `await`, the predefined operators with the numeric promotions, `?:`, `??`, tuples, `switch`, target-typed
 * `new()` / `default`. What it cannot tell is null — never a guess between candidates that disagree.
 */
internal class CSharpExpressionTypes(private val r: CSharpNameResolver) {
    private val inferred = HashMap<Pair<PsiElement, CSharpSymbol>, List<SemanticType?>>()
    private val inferring = HashMap<Pair<PsiElement, CSharpSymbol>, Array<SemanticType?>>()

    fun compute(e: CSharpExpression): SemanticType? {
        literal(e)?.let { return it }
        return when (e) {
            is CSharpParenthesizedExpression -> e.expression?.let(r::typeOf)
            is CSharpThisExpression -> r.syntax.enclosingTypes(e).firstOrNull()?.let(r::selfType)
            is CSharpBaseExpression -> r.syntax.enclosingTypes(e).firstOrNull()?.let(r::selfType)?.let { self ->
                r.baseTypes(self).firstOrNull { it is SemanticType.Source || it is SemanticType.Library && it.type.kind != IndexedTypeKind.INTERFACE }
            }
            is CSharpSimpleName -> simpleName(e)
            is CSharpType -> null
            is CSharpMemberAccessExpression -> e.nameElement?.let { name -> r.resolveName(name)?.single?.let(r::valueType) ?: tupleElement(name) }
            is CSharpMemberBindingExpression -> e.nameElement?.let { name -> r.resolveName(name)?.single?.let(r::valueType) ?: tupleElement(name) }
            is CSharpConditionalAccessExpression -> e.whenNotNull?.let(r::typeOf)?.let(::liftNullable)
            is CSharpElementBindingExpression -> {
                val receiver = r.receiverOfBinding(e) ?: return null
                indexed(receiver, e.argumentList?.arguments.orEmpty())
            }
            is CSharpInvocationExpression -> invocation(e)
            is CSharpObjectCreationExpression -> e.type?.let(r::resolveType)
            is CSharpImplicitObjectCreationExpression -> target(e)
            is CSharpArrayCreationExpression -> e.type?.let(r::resolveType)
            is CSharpImplicitArrayCreationExpression -> {
                val element = common(e.initializer?.expressions.orEmpty()) ?: return null
                SemanticType.ArrayOf(element, e.commas.size + 1)
            }
            is CSharpCastExpression -> e.type?.let(r::resolveType)
            is CSharpDefaultExpression -> e.type?.let(r::resolveType)
            is CSharpTypeOfExpression -> r.libraryType("System.Type")
            is CSharpSizeOfExpression -> r.libraryType("System.Int32")
            is CSharpCheckedExpression -> e.expression?.let(r::typeOf)
            is CSharpAwaitExpression -> e.expression?.let(r::typeOf)?.let(::awaited)
            is CSharpElementAccessExpression -> {
                val receiver = e.expression?.let(r::typeOf) ?: return null
                indexed(receiver, e.argumentList?.arguments.orEmpty())
            }
            is CSharpConditionalExpression -> conditional(e.whenTrue, e.whenFalse)
            is CSharpAssignmentExpression -> assignment(e)
            is CSharpPostfixUnaryExpression -> e.operand?.let(r::typeOf)
            is CSharpPrefixUnaryExpression -> prefix(e)
            is CSharpBinaryExpression -> binary(e)
            is CSharpIsPatternExpression -> r.libraryType("System.Boolean")
            is CSharpTupleExpression -> tuple(e)
            is CSharpSwitchExpression -> common(e.arms.mapNotNull { it.expression })
            is CSharpRangeExpression -> r.libraryType("System.Range")
            is CSharpWithExpression -> e.expression?.let(r::typeOf)
            is CSharpDeclarationExpression -> declaration(e)
            is CSharpQueryExpression -> query(e)
            // `field` of a property accessor: the backing field has the property's type
            is CSharpFieldExpression -> PsiTreeUtil.getParentOfType(e, CSharpBasePropertyDeclaration::class.java)?.type?.let(r::resolveType)
            else -> null
        }
    }

    // ---- literals and names

    private fun literal(e: CSharpExpression): SemanticType? = when (e.elementType) {
        SyntaxKind.StringLiteralExpression, SyntaxKind.InterpolatedStringExpression -> r.libraryType("System.String")
        SyntaxKind.CharacterLiteralExpression -> r.libraryType("System.Char")
        SyntaxKind.TrueLiteralExpression, SyntaxKind.FalseLiteralExpression -> r.libraryType("System.Boolean")
        SyntaxKind.NumericLiteralExpression -> CSharpNameResolver.KEYWORD_TYPES[CSharpNameResolver.numericKeyword(e.text)]?.let(r::libraryType)
        SyntaxKind.Utf8StringLiteralExpression -> r.libraryType("System.ReadOnlySpan`1")?.let { span -> r.libraryType("System.Byte")?.let { SemanticType.Library(span.type, listOf(it)) } }
        SyntaxKind.DefaultLiteralExpression -> target(e)
        else -> null
    }

    private fun simpleName(e: CSharpSimpleName): SemanticType? {
        val symbol = r.resolveName(e)
        // `args` of top-level statements is declared by no one
        if (symbol == null && e.identifier?.text == "args" && r.file.compilationUnit?.members?.any { it is CSharpGlobalStatement } == true) {
            return r.libraryType("System.String")?.let { SemanticType.ArrayOf(it) }
        }
        if (symbol == null) return tupleElement(e)
        return symbol.single?.let(r::valueType)
    }

    /**
     * The type of any expression node as the semantic oracle asks it: a name that stands for a type is that type (`Console` of
     * `Console.WriteLine`, `List<int>` of a declaration), `var` the type it infers, other type syntax what it names.
     */
    fun ofNode(e: CSharpExpression): SemanticType? {
        val rightmost = when (e) {
            is CSharpSimpleName -> e
            is CSharpQualifiedName -> e.right
            is CSharpAliasQualifiedName -> e.nameElement as? CSharpSimpleName
            is CSharpType -> return r.resolveType(e)
            else -> return r.typeOf(e)
        } ?: return null
        val inTypePosition = e is CSharpType && (e !is CSharpSimpleName || NativeCSharpTypePositions.isType(e) || isQualifierOfType(e))
        if (inTypePosition) return r.resolveType(e as CSharpType)
        val symbol = r.resolveName(rightmost)?.single
        return when (symbol) {
            null -> if (e is CSharpSimpleName) r.typeOf(e) ?: e.identifier?.text?.takeIf { it == "nint" || it == "nuint" }?.let { r.resolveType(e) } else null
            is CSharpSymbol.Namespace -> null
            is CSharpSymbol.SourceType, is CSharpSymbol.LibraryType -> r.resolveType(e as CSharpType)
            is CSharpSymbol.Local -> if (symbol.symbol.kind == io.github.dotnetsupport.lang.LocalSymbolKind.TYPE_PARAMETER) r.resolveType(e as CSharpType) else r.typeOf(e)
            else -> if (e is CSharpSimpleName) r.typeOf(e) else null
        }
    }

    private fun isQualifierOfType(e: CSharpSimpleName): Boolean = (e.parent as? CSharpQualifiedName)?.left == e

    // ---- calls

    /** What a call returns: the method it resolves to with its type arguments inferred (all candidates must agree); a delegate's `Invoke`. */
    private fun invocation(call: CSharpInvocationExpression): SemanticType? {
        val callee = callee(call) ?: return null
        if (callee.identifier?.text == "nameof" && call.expression is CSharpSimpleName && r.resolveName(callee) == null) return r.libraryType("System.String")
        val symbols = r.resolveName(callee)?.symbols ?: return null
        val returned = symbols.map { symbol ->
            when {
                r.isMethod(symbol) -> r.returnType(symbol, typeArguments(symbol, call, callee))
                // a local function: what it returns (its generic ones are not inferred)
                symbol is CSharpSymbol.Local && symbol.symbol.kind == io.github.dotnetsupport.lang.LocalSymbolKind.LOCAL_FUNCTION ->
                    r.returnType(symbol, emptyList())?.takeIf { (symbol.symbol.declaration.parent as? CSharpLocalFunctionStatement)?.typeParameterList == null }
                symbol is CSharpSymbol.SourceType || symbol is CSharpSymbol.LibraryType || symbol is CSharpSymbol.Namespace -> null
                else -> r.valueType(symbol)?.let { delegateSignature(it)?.second }
            }
        }
        val first = returned.firstOrNull() ?: return null
        if (returned.size == 1) return first
        val shown = first.display
        if (shown != null && returned.all { it?.display == shown }) return first
        // the candidates disagree on the type arguments: the definition still reaches the members (`a.M().X`), the display is not known
        return if (returned.all { it != null && r.definitionName(it) == r.definitionName(first) } && shown == null) first else null
    }

    private fun callee(call: CSharpInvocationExpression): CSharpSimpleName? = when (val e = call.expression) {
        is CSharpSimpleName -> e
        is CSharpMemberAccessExpression -> e.nameElement
        is CSharpMemberBindingExpression -> e.nameElement
        else -> null
    }

    /** `xs.Where(...)` of an extension method: the receiver is its first argument. */
    private fun isReduced(symbol: CSharpSymbol, callee: CSharpSimpleName): Boolean {
        if (!r.isExtension(symbol)) return false
        return when (val parent = callee.parent) {
            is CSharpMemberAccessExpression -> parent.nameElement == callee && parent.expression?.let(r::qualifier).let { it is CSharpNameResolver.Qualifier.Value || it is CSharpNameResolver.Qualifier.ValueOrType }
            is CSharpMemberBindingExpression -> true
            else -> false
        }
    }

    /**
     * Whether the receiver of a reduced call can be the `this` parameter of the extension method [symbol]: false only when that parameter's
     * type is fully known (no type parameter of the method in it) and the receiver is not that type nor derives from it.
     */
    fun receiverFits(symbol: CSharpSymbol, receiver: SemanticType): Boolean {
        if (!r.isExtension(symbol)) return true
        val parameter = r.signature(symbol, false)?.firstOrNull()?.type?.invoke() ?: return true
        val shown = parameter.display ?: return true
        if (mentionsMethodParameter(parameter)) return true
        if (receiver.display == null) return true
        val definition = (parameter as? SemanticType.Library)?.type?.fullName ?: return true
        if ((parameter as SemanticType.Library).arguments.isEmpty()) return true
        val instance = instanceOf(receiver, definition) ?: return true
        return instance.display == shown
    }

    private fun mentionsMethodParameter(type: SemanticType?): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> type.ofMethod
        is SemanticType.Library -> type.arguments.any(::mentionsMethodParameter)
        is SemanticType.Source -> type.arguments.any(::mentionsMethodParameter)
        is SemanticType.ArrayOf -> mentionsMethodParameter(type.element)
    }

    /**
     * The type of a named tuple element (`pair.Order`, C# §8.3.11): the type argument of ValueTuple at the name's position. The name
     * resolves to no symbol — Roslyn's is the element's own field, declared in the tuple — but the member after it does.
     */
    private fun tupleElement(name: CSharpSimpleName): SemanticType? {
        val parent = name.parent
        if (!(parent is CSharpMemberAccessExpression && parent.nameElement == name) && parent !is CSharpMemberBindingExpression) return null
        val tuple = receiver(name) as? SemanticType.Library ?: return null
        val index = tuple.tupleNames?.indexOf(name.text)?.takeIf { it in 0 until 7 } ?: return null
        return tuple.arguments.getOrNull(index)
    }

    fun receiver(callee: CSharpSimpleName): SemanticType? = when (val parent = callee.parent) {
        is CSharpMemberAccessExpression -> when (val q = parent.expression?.let(r::qualifier)) {
            is CSharpNameResolver.Qualifier.Value -> q.type
            is CSharpNameResolver.Qualifier.ValueOrType -> q.value
            else -> null
        }
        is CSharpMemberBindingExpression -> r.receiverOfBinding(parent)
        else -> null
    }

    private fun methodArity(symbol: CSharpSymbol): Int = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.arity
        is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
            is CSharpMethodDeclaration -> element.typeParameterList?.parameters?.size ?: 0
            is CSharpLocalFunctionStatement -> element.typeParameterList?.parameters?.size ?: 0
            else -> 0
        }
        else -> 0
    }

    /**
     * The type arguments of a call of the generic method [symbol]: written, or inferred (C# §12.6.3, the simple part): each argument of a
     * known type fixes what the type parameters of its parameter's type stand for — through the base types and interfaces of the argument
     * (`List<Order>` for `IEnumerable<TSource>`); then each lambda, its parameters typed by what is fixed so far, fixes the rest by the type of
     * its body (`Select(o => o.Name)`). While a lambda's body is typed, the arguments fixed so far are what its parameters see.
     */
    fun typeArguments(symbol: CSharpSymbol, call: CSharpInvocationExpression, callee: CSharpSimpleName, withLambdas: Boolean = true): List<SemanticType?> {
        val arity = methodArity(symbol)
        if (arity == 0) return emptyList()
        (callee as? CSharpGenericName)?.typeArgumentList?.arguments?.takeIf { it.size == arity }?.let { written -> return written.map(r::resolveType) }
        val key: Pair<PsiElement, CSharpSymbol> = call to symbol
        inferred[key]?.let { return it }
        inferring[key]?.let { return it.toList() }
        val fixed = arrayOfNulls<SemanticType>(arity)
        inferring[key] = fixed
        val cycles = r.cycleCount
        try {
            val reduced = isReduced(symbol, callee)
            val parameters = r.signature(symbol, false) ?: return fixed.toList()
            val owner = (symbol as? CSharpSymbol.SourceMember)?.element
            if (reduced) unify(parameters.firstOrNull()?.type?.invoke(), receiver(callee), fixed, owner)
            val arguments = call.argumentList?.arguments.orEmpty()
            val lambdas = ArrayList<Pair<CSharpAnonymousFunctionExpression, CSharpNameResolver.Parameter>>()
            for ((i, argument) in arguments.withIndex()) {
                val parameter = parameterFor(parameters, arguments, i, if (reduced) 1 else 0) ?: continue
                val expression = argument.expression ?: continue
                if (expression is CSharpAnonymousFunctionExpression) { lambdas += expression to parameter; continue }
                if (expression is CSharpDeclarationExpression) continue
                var type = parameter.type() ?: continue
                val argumentType = r.typeOf(expression) ?: continue
                if (parameter.isParams && type is SemanticType.ArrayOf && argumentType !is SemanticType.ArrayOf) type = type.element ?: continue
                unify(type, argumentType, fixed, owner)
            }
            for ((lambda, parameter) in lambdas) {
                if (!withLambdas) break
                val delegate = parameter.type()?.let(::unwrapExpression) ?: continue
                val returns = delegateSignature(delegate)?.second ?: continue
                if (!mentionsUnfixed(returns, fixed)) continue
                val body = lambdaReturnType(lambda) ?: continue
                unify(returns, body, fixed, owner)
            }
            // an answer that met a question in progress (a lambda typed while its call is being resolved) is not kept
            return fixed.toList().also { if (withLambdas && r.cycleCount == cycles) inferred[key] = it }
        } finally {
            inferring.remove(key)
        }
    }

    private fun mentionsUnfixed(type: SemanticType?, fixed: Array<SemanticType?>): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> type.ofMethod && type.index in fixed.indices && fixed[type.index] == null
        is SemanticType.Library -> type.arguments.any { mentionsUnfixed(it, fixed) }
        is SemanticType.Source -> type.arguments.any { mentionsUnfixed(it, fixed) }
        is SemanticType.ArrayOf -> mentionsUnfixed(type.element, fixed)
    }

    /** The parameter the [i]-th argument goes to: by name, by position, the `params` one past the end. */
    private fun parameterFor(parameters: List<CSharpNameResolver.Parameter>, arguments: List<CSharpArgument>, i: Int, offset: Int): CSharpNameResolver.Parameter? {
        val name = arguments[i].nameColon?.nameElement?.identifier?.text
        if (name != null) return parameters.firstOrNull { it.name == name }
        return parameters.getOrNull(i + offset) ?: parameters.lastOrNull()?.takeIf { it.isParams }
    }

    /** Fixes the method type parameters in [parameter] by what [argument] has in their places. */
    private fun unify(parameter: SemanticType?, argument: SemanticType?, fixed: Array<SemanticType?>, owner: PsiElement?, depth: Int = 0) {
        if (parameter == null || argument == null || depth > 8) return
        when (parameter) {
            is SemanticType.Parameter -> if (parameter.ofMethod && parameter.index in fixed.indices && (parameter.owner == null || parameter.owner == owner) && fixed[parameter.index] == null) {
                fixed[parameter.index] = argument
            }
            is SemanticType.ArrayOf -> if (argument is SemanticType.ArrayOf) unify(parameter.element, argument.element, fixed, owner, depth + 1)
            is SemanticType.Library -> {
                if (parameter.arguments.isEmpty()) return
                if (parameter.type.fullName == NULLABLE && !isNullable(argument)) return unify(parameter.arguments.firstOrNull(), argument, fixed, owner, depth + 1)
                val instance = instanceOf(argument, parameter.type.fullName) ?: return
                for ((p, a) in parameter.arguments.zip(instance.arguments)) unify(p, a, fixed, owner, depth + 1)
            }
            is SemanticType.Source -> {
                if (parameter.arguments.isEmpty()) return
                val instance = sourceInstanceOf(argument, parameter.info.key, 0) ?: return
                for ((p, a) in parameter.arguments.zip(instance.arguments)) unify(p, a, fixed, owner, depth + 1)
            }
        }
    }

    internal fun sourceInstanceOf(type: SemanticType, key: String, depth: Int): SemanticType.Source? {
        if (type !is SemanticType.Source || depth > 16) return null
        if (type.info.key == key) return type
        return r.baseTypes(type).firstNotNullOfOrNull { sourceInstanceOf(it, key, depth + 1) }
    }

    /**
     * [type] seen as the generic type [definition] (a full metadata name, `System.Collections.Generic.IEnumerable`1`): itself, or the base
     * type or interface of that definition it has, with its type arguments in [type]'s terms; arrays are their collection interfaces.
     */
    fun instanceOf(type: SemanticType, definition: String, depth: Int = 0): SemanticType.Library? {
        if (depth > 16) return null
        return when (type) {
            is SemanticType.Library -> {
                if (type.type.fullName == definition) return type
                val assemblies = r.assemblies
                val supertype = r.session.baseTypes(assemblies, type.type).firstOrNull { it.type.fullName == definition }
                    ?: r.session.interfaces(assemblies, type.type).firstOrNull { it.type.fullName == definition } ?: return null
                SemanticType.Library(supertype.type, supertype.arguments.map { r.fromRef(it, type.arguments) })
            }
            is SemanticType.Source -> r.baseTypes(type).firstNotNullOfOrNull { instanceOf(it, definition, depth + 1) }
            is SemanticType.ArrayOf -> if (definition in ARRAY_INTERFACES) r.libraryType(definition)?.let { SemanticType.Library(it.type, listOf(type.element)) }
            else r.libraryType("System.Array")?.let { instanceOf(it, definition, depth + 1) }
            is SemanticType.Parameter -> r.constraintsOf(type).firstNotNullOfOrNull { instanceOf(it, definition, depth + 1) }
        }
    }

    // ---- lambdas

    /** The type of a parameter of a lambda without written types: the parameter of the delegate the lambda converts to. */
    fun lambdaParameterType(parameter: CSharpParameter): SemanticType? {
        val lambda: CSharpAnonymousFunctionExpression
        val index: Int
        when (val parent = parameter.parent) {
            is CSharpSimpleLambdaExpression -> { lambda = parent; index = 0 }
            is CSharpParameterList -> {
                lambda = parent.parent as? CSharpAnonymousFunctionExpression ?: return null
                index = parent.parameters.indexOf(parameter)
            }
            else -> return null
        }
        var at: PsiElement = lambda
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val argument = at.parent as? CSharpArgument
        if (argument != null) {
            // overloads that differ in what the delegate returns (`Sum(x => x.Total)`) still agree on what it takes
            val found = parameterTypesOf(argument, lambdaParameterCount(lambda)).map { type -> unwrapExpression(type)?.let(::delegateSignature)?.first?.getOrNull(index) ?: return null }
            val first = found.firstOrNull() ?: return null
            return if (found.all { it.display != null && it.display == first.display }) first else null
        }
        return delegateOf(lambda)?.let(::delegateSignature)?.first?.getOrNull(index)
    }

    /**
     * False when [lambda] surely cannot convert to [parameterType] (C# §10.7.1): a delegate that takes another number of parameters; one
     * returning `void` for a body that is a value and no statement (`() => "x"` is no `Action`); one returning a value for a block that
     * returns none, or for a body whose type surely does not convert (`() => "x"` is no `Func<Task>`).
     */
    fun lambdaFits(lambda: CSharpAnonymousFunctionExpression, parameterType: SemanticType, typeBody: Boolean = true): Boolean {
        val delegate = unwrapExpression(parameterType)?.let(::delegateSignature) ?: return true
        val count = lambdaParameterCount(lambda)
        if (count != null && delegate.first.size != count) return false
        // explicitly typed parameters take only a delegate with exactly those types (§10.7.1): `(int id) => …` is no `RequestDelegate`
        val written = (lambda as? CSharpParenthesizedLambdaExpression)?.parameterList?.parameters ?: (lambda as? CSharpAnonymousMethodExpression)?.parameterList?.parameters
        if (written != null) for ((p, expected) in written.zip(delegate.first)) {
            val type = p.type?.let(r::resolveType) ?: continue
            if (expected != null && r.overloads.same(type, expected) == false && !mentionsMethodParameter(expected)) return false
        }
        val returns = delegate.second ?: return true
        val void = r.definitionName(returns) == "System.Void"
        val body = lambda.expressionBody
        if (isAsync(lambda.modifiers)) return true
        if (body == null) {
            val block = lambda.block ?: return true
            return void || firstReturn(block) != null || !completesNormally(block)
        }
        if (void) return isStatementExpression(body)
        // a body that uses the lambda's parameters is typed once the call is resolved (refineByLambdas), not for every overload
        if (!typeBody) return true
        val type = r.typeOf(body) ?: return true
        if (mentionsMethodParameter(returns)) {
            if (returns !is SemanticType.Library || returns.arguments.isEmpty()) return true
            return instanceOf(type, returns.type.fullName) != null || r.definitionName(type) == null
        }
        return r.conversion(type, returns) != CSharpNameResolver.Conversion.NONE
    }

    /**
     * Of the overloads [candidates] of a call at [site] that has lambdas among its arguments, the ones whose delegates take the lambdas'
     * bodies, preferring those that return exactly the type of the bodies (`Sum(x => x.Total)` with a `decimal` `Total`). Asked when the
     * name is resolved to the candidates already: typing the bodies needs the lambdas' parameters, which every candidate gives the same.
     */
    fun refineByLambdas(site: CSharpSimpleName, candidates: List<CSharpSymbol>): List<CSharpSymbol> {
        if (candidates.size < 2 || candidates.any { !r.isMethod(it) }) return candidates
        val call = r.invocationOf(site) ?: return candidates
        val arguments = call.argumentList?.arguments.orEmpty()
        if (arguments.none { it.expression is CSharpAnonymousFunctionExpression }) return candidates
        val scored = candidates.mapNotNull { symbol ->
            val parameters = r.signature(symbol, false) ?: return@mapNotNull symbol to 0
            val offset = if (isReduced(symbol, site)) 1 else 0
            var exact = 0
            for ((i, argument) in arguments.withIndex()) {
                val lambda = argument.expression as? CSharpAnonymousFunctionExpression ?: continue
                // the parameter as declared: what the delegate returns decides, inferring the method's type arguments is not needed for it
                val type = parameterFor(parameters, arguments, i, offset)?.type?.invoke() ?: continue
                val returns = unwrapExpression(type)?.let(::delegateSignature)?.second
                if (returns is SemanticType.Parameter && returns.ofMethod) { exact++; continue }
                if (!lambdaFits(lambda, type)) return@mapNotNull null
                val body = lambda.expressionBody?.let(r::typeOf)
                if (returns != null && body != null && same(returns, body)) exact++
            }
            symbol to exact
        }
        if (scored.isEmpty()) return candidates
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.map { it.first }
    }

    private fun isStatementExpression(e: CSharpExpression): Boolean = e is CSharpInvocationExpression || e is CSharpAssignmentExpression ||
        e is CSharpAwaitExpression || e is CSharpBaseObjectCreationExpression || e is CSharpPostfixUnaryExpression ||
        (e is CSharpPrefixUnaryExpression && (e.operatorToken?.text == "++" || e.operatorToken?.text == "--")) || e is CSharpThrowExpression

    /** A block that ends in `throw` never returns: it converts to any delegate. */
    private fun completesNormally(block: CSharpBlock): Boolean = block.statements.lastOrNull() !is CSharpThrowStatement

    fun lambdaParameterCount(lambda: CSharpAnonymousFunctionExpression): Int? = when (lambda) {
        is CSharpSimpleLambdaExpression -> 1
        is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters?.size
        is CSharpAnonymousMethodExpression -> lambda.parameterList?.parameters?.size
        else -> null
    }

    /** The delegate type a lambda converts to, where its place says it: a parameter of the called method, a declared variable, a cast. */
    fun delegateOf(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
        var at: PsiElement = lambda
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val type = when (val parent = at.parent) {
            is CSharpArgument -> parameterTypeOf(parent, lambdaParameterCount(lambda))
            is CSharpCastExpression -> parent.type?.let(r::resolveType)
            is CSharpAssignmentExpression -> if (parent.right == at) parent.left?.let(r::typeOf) else null
            is CSharpEqualsValueClause -> declaredTarget(parent)
            is CSharpReturnStatement, is CSharpArrowExpressionClause -> returnTarget(parent)
            else -> null
        } ?: return null
        return unwrapExpression(type)
    }

    /** The type of the parameter [argument] goes to, the method type arguments filled in; candidates must agree. */
    private fun parameterTypeOf(argument: CSharpArgument, lambdaParameters: Int? = null): SemanticType? {
        val found = parameterTypesOf(argument, lambdaParameters)
        val first = found.firstOrNull() ?: return null
        return if (found.all { it.display != null && it.display == first.display }) first else null
    }

    /** The type of the parameter [argument] goes to in each candidate of the call, the method type arguments filled in. */
    private fun parameterTypesOf(argument: CSharpArgument, lambdaParameters: Int?): List<SemanticType> {
        val list = argument.parent as? CSharpArgumentList ?: return emptyList()
        val call = list.parent as? CSharpInvocationExpression ?: return emptyList()
        val callee = callee(call) ?: return emptyList()
        val symbols = r.resolveName(callee)?.symbols ?: return emptyList()
        val arguments = list.arguments
        val i = arguments.indexOf(argument)
        return symbols.filter(r::isMethod).mapNotNull { symbol ->
            val reduced = isReduced(symbol, callee)
            val parameters = r.signature(symbol, false) ?: return@mapNotNull null
            var type = parameterFor(parameters, arguments, i, if (reduced) 1 else 0)?.type?.invoke() ?: return@mapNotNull null
            val methodArguments = typeArguments(symbol, call, callee)
            if (methodArguments.isNotEmpty()) type = substituteMethod(type, methodArguments) ?: return@mapNotNull null
            if (lambdaParameters != null) {
                val count = unwrapExpression(type)?.let(::delegateSignature)?.first?.size
                if (count != null && count != lambdaParameters) return@mapNotNull null
            }
            type
        }
    }

    private fun substituteMethod(type: SemanticType, arguments: List<SemanticType?>): SemanticType? =
        r.replace(type) { p -> if (p.ofMethod) arguments.getOrNull(p.index) ?: p else p }

    private fun declaredTarget(clause: CSharpEqualsValueClause): SemanticType? = when (val owner = clause.parent) {
        is CSharpVariableDeclarator -> (owner.parent as? CSharpVariableDeclaration)?.type?.takeIf { !r.isVar(it) }?.let(r::resolveType)
        is CSharpPropertyDeclaration -> owner.type?.let(r::resolveType)
        is CSharpParameter -> owner.type?.let(r::resolveType)
        else -> null
    }

    /** What a `return` / `=>` of the function around it returns (a method, a local function, a property, an operator), `Task<T>` unwrapped for `async`. */
    private fun returnTarget(at: PsiElement): SemanticType? {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            val declared: SemanticType? = when (current) {
                is CSharpAnonymousFunctionExpression -> return delegateOf(current)?.let(::delegateSignature)?.second?.let { if (isAsync(current.modifiers)) awaitedOrNull(it) else it }
                is CSharpLocalFunctionStatement -> current.returnType?.let(r::resolveType)?.let { if (isAsync(current.modifiers)) awaitedOrNull(it) else it }
                is CSharpMethodDeclaration -> current.returnType?.let(r::resolveType)?.let { if (isAsync(current.modifiers)) awaitedOrNull(it) else it }
                is CSharpOperatorDeclaration -> current.returnType?.let(r::resolveType)
                is CSharpConversionOperatorDeclaration -> current.type?.let(r::resolveType)
                is CSharpBasePropertyDeclaration -> current.type?.let(r::resolveType)
                else -> null
            }
            if (declared != null || current is CSharpMemberDeclaration) return declared
            current = current.parent
        }
        return null
    }

    internal fun isAsync(modifiers: List<PsiElement>): Boolean = modifiers.any { it.text == "async" }

    private fun awaitedOrNull(type: SemanticType): SemanticType? = (type as? SemanticType.Library)?.takeIf { it.type.fullName in TASKS_OF_T }?.arguments?.firstOrNull()

    /** `Expression<Func<T>>` converts a lambda as its delegate does. */
    internal fun unwrapExpression(type: SemanticType): SemanticType? =
        if (type is SemanticType.Library && type.type.fullName == "System.Linq.Expressions.Expression`1") type.arguments.firstOrNull() else type

    /** The parameter types and the return type of a delegate type (its `Invoke`), substituted. */
    fun delegateSignature(type: SemanticType): Pair<List<SemanticType?>, SemanticType?>? = when (type) {
        is SemanticType.Library -> {
            if (type.type.kind != IndexedTypeKind.DELEGATE) null
            else type.type.members.firstOrNull { it.name == "Invoke" }?.let { invoke ->
                invoke.parameters.map { r.fromRef(it.typeRef, type.arguments) } to r.fromRef(invoke.typeRef, type.arguments)
            }
        }
        is SemanticType.Source -> {
            val declaration = type.info.parts.firstNotNullOfOrNull { it.element() as? CSharpDelegateDeclaration }
            val resolver = (declaration?.containingFile as? CSharpFile)?.let(r.session::reachable)
            if (declaration == null || resolver == null) null
            else declaration.parameterList?.parameters.orEmpty().map { p -> r.substitute(p.type?.let(resolver::resolveType), type) } to r.substitute(declaration.returnType?.let(resolver::resolveType), type)
        }
        else -> null
    }

    /** The type a lambda's body gives: its expression, or the first `return` with a value of its block; `Task<T>` of an `async` one. */
    fun lambdaReturnType(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
        val body = lambda.expressionBody?.let(r::typeOf) ?: lambda.block?.let(::firstReturn)?.let(r::typeOf)
        if (!isAsync(lambda.modifiers)) return body
        val task = r.libraryType(if (body == null) "System.Threading.Tasks.Task" else "System.Threading.Tasks.Task`1") ?: return null
        return if (body == null) task else SemanticType.Library(task.type, listOf(body))
    }

    private fun firstReturn(element: PsiElement): CSharpExpression? {
        var child = element.firstChild
        while (child != null) {
            if (child is CSharpReturnStatement) child.expression?.let { return it }
            if (child !is CSharpAnonymousFunctionExpression && child !is CSharpLocalFunctionStatement) firstReturn(child)?.let { return it }
            child = child.nextSibling
        }
        return null
    }

    // ---- locals the declarations do not type

    /** `out var x`: the type of the parameter the argument goes to. */
    fun outVariableType(declaration: CSharpDeclarationExpression): SemanticType? {
        var at: PsiElement = declaration
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val argument = at.parent as? CSharpArgument ?: return null
        return parameterTypeOf(argument)
    }

    /** A designation of `var (a, b) = ...`, `(var a, var b) = ...` or `foreach (var (a, b) in ...)`: the element of what is deconstructed. */
    fun deconstructedType(designation: CSharpSingleVariableDesignation): SemanticType? {
        // the path of positions from the designation up to the whole deconstruction
        val path = ArrayList<Int>()
        var at: PsiElement = designation
        while (true) {
            val parent = at.parent ?: return null
            when {
                parent is CSharpParenthesizedVariableDesignation -> path += parent.variables.indexOf(at)
                parent is CSharpDeclarationExpression -> {}
                parent is CSharpArgument && parent.parent is CSharpTupleExpression -> {
                    val tuple = parent.parent as CSharpTupleExpression
                    path += tuple.arguments.indexOf(parent)
                    at = tuple
                    continue
                }
                else -> break
            }
            at = parent
        }
        val holder = at.parent
        var type: SemanticType? = when {
            holder is CSharpAssignmentExpression && holder.left == at -> holder.right?.let(r::typeOf)
            holder is CSharpForEachVariableStatement && holder.variable == at -> holder.expression?.let(r::typeOf)?.let(r::elementType)
            else -> null
        }
        for (index in path.asReversed()) type = type?.let { deconstruct(it, index) }
        return type
    }

    /** The [index]-th part of a value deconstructed: a tuple's element, or the `out` parameter of its `Deconstruct`. */
    private fun deconstruct(type: SemanticType, index: Int): SemanticType? {
        if (type is SemanticType.Library && type.type.fullName.startsWith("System.ValueTuple`")) return type.arguments.getOrNull(index)
        val methods = r.membersNamed(type, "Deconstruct", 0).filter(r::isMethod)
        val method = methods.singleOrNull() ?: return null
        return r.signature(method, false)?.getOrNull(index)?.type?.invoke()
    }

    /** What a `var` pattern or a pattern without a type (`{ } x`) gets: the type of what is tested. */
    fun patternInputType(pattern: CSharpPattern): SemanticType? {
        var at: PsiElement = pattern
        while (true) {
            val parent = at.parent ?: return null
            when (parent) {
                is CSharpBinaryPattern, is CSharpParenthesizedPattern, is CSharpUnaryPattern -> at = parent
                is CSharpIsPatternExpression -> return parent.expression?.let(r::typeOf)
                is CSharpSwitchExpressionArm -> return (parent.parent as? CSharpSwitchExpression)?.governingExpression?.let(r::typeOf)
                is CSharpCasePatternSwitchLabel -> return (parent.parent?.parent as? CSharpSwitchStatement)?.expression?.let(r::typeOf)
                else -> return null
            }
        }
    }

    // ---- queries over IEnumerable<T> (C# §12.20.3: the clauses are calls of Enumerable's methods)

    private fun enumerableOf(element: SemanticType?): SemanticType? =
        element?.let { e -> r.libraryType("System.Collections.Generic.IEnumerable`1")?.let { SemanticType.Library(it.type, listOf(e)) } }

    /** The element type a query source gives its range variable; null for what is no `IEnumerable<T>` (an `IQueryable<T>` maps elsewhere). */
    private fun querySource(source: CSharpExpression?): SemanticType? {
        val type = source?.let(r::typeOf) ?: return null
        if (type is SemanticType.ArrayOf) return type.element   // without System.Runtime too
        if (instanceOf(type, "System.Linq.IQueryable`1") != null) return null
        return instanceOf(type, "System.Collections.Generic.IEnumerable`1")?.arguments?.firstOrNull()
    }

    /** The type of a range variable declared by [clause] (`from`, `let`, `join`, `join ... into`, `into`). */
    fun rangeVariableType(clause: PsiElement): SemanticType? = when (clause) {
        is CSharpFromClause -> clause.type?.let(r::resolveType) ?: querySource(clause.expression)
        is CSharpLetClause -> clause.expression?.let(r::typeOf)
        is CSharpJoinClause -> clause.type?.let(r::resolveType) ?: querySource(clause.inExpression)
        is CSharpJoinIntoClause -> (clause.parent as? CSharpJoinClause)?.let(::rangeVariableType)?.let(::enumerableOf)
        // the element itself, not taken back out of `IEnumerable<T>`: that needs System.Runtime, an `IGrouping<K, T>` only System.Linq
        is CSharpQueryContinuation -> (clause.parent as? CSharpQueryBody)?.let(::queryBodyElement)
        else -> null
    }

    private fun query(e: CSharpQueryExpression): SemanticType? {
        if (e.fromClause?.let(::rangeVariableType) == null) return null
        return e.body?.let { queryBodyType(it, false) }
    }

    private fun queryBodyType(body: CSharpQueryBody, ignoreContinuation: Boolean): SemanticType? {
        if (!ignoreContinuation) body.continuation?.body?.let { return queryBodyType(it, false) }
        val selected = (body.selectOrGroup as? CSharpSelectClause)?.expression
        // `orderby ... select x`: a degenerate select is left out, the type is OrderBy's
        if (selected != null && body.clauses.lastOrNull() is CSharpOrderByClause && isRangeVariable(selected)) {
            val ordered = r.libraryType("System.Linq.IOrderedEnumerable`1") ?: return null
            return r.typeOf(selected)?.let { SemanticType.Library(ordered.type, listOf(it)) }
        }
        return enumerableOf(queryBodyElement(body))
    }

    /** What the select or group of [body] yields element by element: the selected value, the `IGrouping<K, T>` of `group … by`. */
    private fun queryBodyElement(body: CSharpQueryBody): SemanticType? = when (val last = body.selectOrGroup) {
        is CSharpGroupClause -> {
            // an argument of no known type stays unknown: `g.Key` is still the member of `IGrouping`
            r.libraryType("System.Linq.IGrouping`2")?.let { SemanticType.Library(it.type, listOf(last.byExpression?.let(r::typeOf), last.groupExpression?.let(r::typeOf))) }
        }
        is CSharpSelectClause -> last.expression?.let(r::typeOf)
        else -> null
    }

    private fun isRangeVariable(e: CSharpExpression): Boolean {
        val name = e as? CSharpIdentifierName ?: return false
        val declaration = name.identifier?.let(r.syntax::symbolAt)?.declaration?.parent
        return declaration is CSharpFromClause || declaration is CSharpLetClause || declaration is CSharpJoinClause || declaration is CSharpQueryContinuation || declaration is CSharpJoinIntoClause
    }

    // ---- element access, await, conversions of the target

    /** `a[i]`: an array's element, `string`'s `char`, the indexer of the type that fits the arguments. */
    fun indexed(receiver: SemanticType, arguments: List<CSharpArgument>): SemanticType? {
        val ranged = arguments.size == 1 && arguments.single().expression is CSharpRangeExpression
        return when (receiver) {
            is SemanticType.ArrayOf -> if (ranged) receiver else receiver.element
            is SemanticType.Library -> {
                if (receiver.type.fullName == "System.String") return if (ranged) receiver else r.libraryType("System.Char")
                val indexers = r.session.libraryMembers(r.assemblies, receiver.type)["Item"].orEmpty().filter { inherited ->
                    val parameters = r.session.parameters(inherited.member)
                    inherited.member.kind == IndexedMemberKind.INDEXER && arguments.size <= parameters.size && parameters.drop(arguments.size).all { it.isOptional || it.hasDefault || it.isParams }
                }
                val chosen = indexers.singleOrNull() ?: run {
                    val types = arguments.map { a -> a.expression?.let(r::typeOf) }
                    indexers.filter { inherited -> r.session.parameters(inherited.member).zip(types).all { (p, t) -> t == null || r.fromRef(p.typeRef, r.declaringArguments(receiver, inherited.from))?.let { r.conversion(t, it) } in OK } }.singleOrNull()
                } ?: return if (ranged) slice(receiver) else null
                r.fromRef(chosen.member.typeRef, r.declaringArguments(receiver, chosen.from))
            }
            is SemanticType.Source -> {
                for (part in receiver.info.parts) {
                    val declaration = part.element() as? CSharpTypeDeclaration ?: continue
                    val resolver = (declaration.containingFile as? CSharpFile)?.let(r.session::reachable) ?: continue
                    val indexer = declaration.members.filterIsInstance<CSharpIndexerDeclaration>().filter { it.parameterList?.parameters?.size == arguments.size }.singleOrNull() ?: continue
                    return r.substitute(indexer.type?.let(resolver::resolveType), receiver)
                }
                r.libraryBases(receiver).firstNotNullOfOrNull { indexed(it, arguments) }
            }
            is SemanticType.Parameter -> null
        }
    }

    /** `span[1..]`: a `Slice` of the same type, as the language makes ranges of `Span` and `ReadOnlySpan`. */
    private fun slice(receiver: SemanticType.Library): SemanticType? =
        if (r.membersNamed(receiver, "Slice", 0).isNotEmpty() && receiver.type.fullName.startsWith("System.") && receiver.type.fullName.contains("Span`")) receiver else null

    /** `await x`: `Task<T>` and the like give `T`, `Task` gives `void`; else the awaiter pattern, `GetAwaiter().GetResult()`. */
    fun awaited(type: SemanticType): SemanticType? {
        val library = type as? SemanticType.Library
        if (library != null) {
            when (library.type.fullName) {
                in TASKS_OF_T -> return library.arguments.firstOrNull()
                "System.Threading.Tasks.Task", "System.Threading.Tasks.ValueTask", "System.Runtime.CompilerServices.ConfiguredTaskAwaitable",
                "System.Runtime.CompilerServices.ConfiguredValueTaskAwaitable", "System.Runtime.CompilerServices.YieldAwaitable" -> return r.libraryType("System.Void")
            }
        }
        val awaiter = r.membersNamed(type, "GetAwaiter", 0).singleOrNull { r.isMethod(it) }?.let { r.returnType(it, emptyList()) } ?: return null
        val result = r.membersNamed(awaiter, "GetResult", 0).singleOrNull { r.isMethod(it) } ?: return null
        return r.returnType(result, emptyList())
    }

    /** Where `new()` or `default` stands: the type of what it initializes, is assigned to, returned as, passed for. */
    fun target(e: CSharpExpression): SemanticType? {
        var at: PsiElement = e
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        return when (val parent = at.parent) {
            is CSharpEqualsValueClause -> declaredTarget(parent)
            is CSharpAssignmentExpression -> if (parent.right == at) parent.left?.let(r::typeOf) else null
            is CSharpReturnStatement, is CSharpArrowExpressionClause -> returnTarget(parent)
            is CSharpArgument -> parameterTypeOf(parent)
            is CSharpCastExpression -> parent.type?.let(r::resolveType)
            is CSharpConditionalExpression -> if (parent.condition != at) {
                val other = if (parent.whenTrue == at) parent.whenFalse else parent.whenTrue
                other?.takeIf { !isTargetTyped(it) }?.let(r::typeOf) ?: target(parent)
            } else null
            is CSharpBinaryExpression -> if (parent.operatorToken?.text == "??" && parent.right == at) parent.left?.let(r::typeOf)?.let(r::unwrapNullable) else null
            // `[new() { … }]`: an element of a collection expression converts to the element type of the collection's target
            is CSharpExpressionElement -> (parent.parent as? CSharpCollectionExpression)?.let(::target)?.let(r::elementType)
            else -> null
        }
    }

    private fun isTargetTyped(e: CSharpExpression): Boolean =
        e.elementType == SyntaxKind.DefaultLiteralExpression || e.elementType == SyntaxKind.NullLiteralExpression || e is CSharpImplicitObjectCreationExpression || e is CSharpThrowExpression

    private fun declaration(e: CSharpDeclarationExpression): SemanticType? {
        val type = e.type
        if (type != null && !r.isVar(type)) return r.resolveType(type)
        return when (val designation = e.designation) {
            is CSharpSingleVariableDesignation -> outVariableType(e) ?: deconstructedType(designation)
            else -> null
        }
    }

    // ---- operators

    private fun liftNullable(type: SemanticType): SemanticType {
        if (!r.isValueType(type) || isNullable(type) || r.definitionName(type) == "System.Void") return type
        return r.libraryType(NULLABLE)?.let { SemanticType.Library(it.type, listOf(type)) } ?: type
    }

    private fun isNullable(type: SemanticType): Boolean = type is SemanticType.Library && type.type.fullName == NULLABLE

    private fun assignment(e: CSharpAssignmentExpression): SemanticType? {
        val left = e.left ?: return null
        val operator = e.operatorToken?.text
        if ((operator == "+=" || operator == "-=") && left.let { l -> (l as? CSharpSimpleName ?: (l as? CSharpMemberAccessExpression)?.nameElement)?.let(r::resolveName)?.single?.let(::isEvent) } == true) {
            return r.libraryType("System.Void")
        }
        if (left is CSharpIdentifierName && left.identifier?.text == "_" && r.resolveName(left) == null) return e.right?.let(r::typeOf)
        if (left is CSharpTupleExpression || left is CSharpDeclarationExpression) return r.typeOf(left)
        if (operator == "??=") return left.let(r::typeOf)?.let(r::unwrapNullable)?.let { l -> e.right?.let(r::typeOf)?.takeIf { same(it, l) } ?: l }
        return r.typeOf(left)
    }

    private fun isEvent(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.EVENT
        is CSharpSymbol.SourceMember -> symbol.member.referenceKey == io.github.dotnetsupport.lang.CSharpColors.EVENT
        else -> false
    }

    private fun prefix(e: CSharpPrefixUnaryExpression): SemanticType? {
        val operand = e.operand ?: return null
        return when (e.operatorToken?.text) {
            "!" -> r.typeOf(operand)?.let { if (r.definitionName(it) == BOOL || isNullable(it)) it else userOperator("op_LogicalNot", it, null) }
            "++", "--" -> r.typeOf(operand)
            "^" -> r.libraryType("System.Index")
            "-", "+", "~" -> {
                val type = r.typeOf(operand) ?: return null
                val nullable = isNullable(type)
                val core = if (nullable) r.unwrapNullable(type) else type
                val name = r.definitionName(core)
                val result = when {
                    name == null -> null
                    isEnum(core) && e.operatorToken?.text == "~" -> core
                    name in SMALL -> r.libraryType(INT)
                    name == "System.UInt32" && e.operatorToken?.text == "-" -> r.libraryType(LONG)
                    name == "System.UInt64" && e.operatorToken?.text == "-" -> null
                    name in NUMERIC -> core
                    else -> userOperator(when (e.operatorToken?.text) { "-" -> "op_UnaryNegation"; "+" -> "op_UnaryPlus"; else -> "op_OnesComplement" }, core, null)
                } ?: return null
                if (nullable) liftNullable(result) else result
            }
            else -> null
        }
    }

    private fun binary(e: CSharpBinaryExpression): SemanticType? {
        val operator = e.operatorToken?.text ?: return null
        return when (operator) {
            "as" -> (e.right as? CSharpType)?.let(r::resolveType)
            "is", "==", "!=", "<", ">", "<=", ">=", "&&", "||" -> r.libraryType(BOOL)
            "??" -> coalesce(e)
            "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>" -> arithmetic(e, operator)
            else -> null
        }
    }

    private fun coalesce(e: CSharpBinaryExpression): SemanticType? {
        val left = e.left?.let(r::typeOf) ?: return null
        val right = e.right?.takeIf { it !is CSharpThrowExpression && !isTargetTyped(it) }?.let(r::typeOf)
        val core = r.unwrapNullable(left)
        if (right == null) return core
        if (isNullable(left)) {
            if (same(right, core) || implicitly(right, core)) return core
            if (same(right, left)) return left
            return null
        }
        if (same(right, left) || implicitly(right, left)) return left
        if (implicitly(left, right)) return right
        return null
    }

    private fun arithmetic(e: CSharpBinaryExpression, operator: String): SemanticType? {
        val leftExpression = e.left ?: return null
        val rightExpression = e.right ?: return null
        val left = r.typeOf(leftExpression)
        val right = r.typeOf(rightExpression)
        if (operator == "+" && (left?.let(r::definitionName) == STRING || right?.let(r::definitionName) == STRING)) return r.libraryType(STRING)
        if (left == null || right == null) return null
        val nullable = isNullable(left) || isNullable(right)
        val l = r.unwrapNullable(left)
        val rt = r.unwrapNullable(right)
        val ln = r.definitionName(l)
        val rn = r.definitionName(rt)
        val result: SemanticType? = when {
            operator == "<<" || operator == ">>" || operator == ">>>" -> if (ln in SMALL) r.libraryType(INT) else if (ln in INTEGRAL) l else userOperator(OPERATORS[operator]!!, l, rt)
            ln == BOOL && rn == BOOL && operator in setOf("&", "|", "^") -> l
            isEnum(l) || isEnum(rt) -> enumArithmetic(l, rt, operator)
            ln in NUMERIC && rn in NUMERIC -> promote(l, rt, leftExpression, rightExpression)
            // generic math, `T + T` of `where T : INumber<T>`: the operators of the constraints return `T`
            l is SemanticType.Parameter && rt is SemanticType.Parameter && l.name == rt.name && r.constraintsOf(l).isNotEmpty() -> l
            else -> userOperator(OPERATORS[operator] ?: return null, l, rt)
        }
        return if (result != null && nullable) liftNullable(result) else result
    }

    private fun isEnum(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.ENUM
        is SemanticType.Source -> type.info.kind == io.github.dotnetsupport.lang.TypeKind.ENUM
        else -> false
    }

    private fun enumArithmetic(left: SemanticType, right: SemanticType, operator: String): SemanticType? = when {
        operator in setOf("&", "|", "^") && same(left, right) -> left
        operator == "+" && isEnum(left) && !isEnum(right) -> left
        operator == "+" && isEnum(right) && !isEnum(left) -> right
        operator == "-" && isEnum(left) && !isEnum(right) -> left
        else -> null
    }

    /** C# §12.4.7.3, binary numeric promotion; an `int` constant goes with `uint` / `ulong` / `long` without widening them. */
    private fun promote(left: SemanticType, right: SemanticType, leftExpression: CSharpExpression, rightExpression: CSharpExpression): SemanticType? {
        val l = r.definitionName(left)!!
        val rn = r.definitionName(right)!!
        if (l == rn) return if (l in SMALL) r.libraryType(INT) else left
        if (isIntConstant(rightExpression) && l in WIDE) return left
        if (isIntConstant(leftExpression) && rn in WIDE) return right
        val names = setOf(l, rn)
        fun has(name: String) = name in names
        // `nint` / `nuint`: with what converts to them implicitly they stay; `long` / `ulong` win over them
        if (has("System.IntPtr") || has("System.UIntPtr")) {
            val native = if (has("System.IntPtr")) "System.IntPtr" else "System.UIntPtr"
            val other = if (l == native) rn else l
            return when {
                other == "System.Int64" || other == "System.UInt64" || other == "System.Single" || other == "System.Double" || other == DECIMAL -> r.libraryType(other)
                NUMERIC_CONVERSIONS_TO_NATIVE[native].orEmpty().contains(other) -> r.libraryType(native)
                else -> null
            }
        }
        return when {
            has(DECIMAL) -> if (has("System.Single") || has("System.Double")) null else r.libraryType(DECIMAL)
            has("System.Double") -> r.libraryType("System.Double")
            has("System.Single") -> r.libraryType("System.Single")
            has("System.UInt64") -> if (names.any { it in SIGNED }) null else r.libraryType("System.UInt64")
            has(LONG) -> r.libraryType(LONG)
            has("System.UInt32") -> if (names.any { it in SIGNED }) r.libraryType(LONG) else r.libraryType("System.UInt32")
            else -> r.libraryType(INT)
        }
    }

    private fun isIntConstant(e: CSharpExpression): Boolean {
        var at = e
        while (at is CSharpParenthesizedExpression) at = at.expression ?: return false
        return at.elementType == SyntaxKind.NumericLiteralExpression && CSharpNameResolver.numericKeyword(at.text) == "int"
    }

    /** A user-defined operator of a type of the assemblies (`DateTime - DateTime`): the one whose parameters take the operands. */
    private fun userOperator(name: String, left: SemanticType, right: SemanticType?): SemanticType? {
        val candidates = (r.membersNamed(left, name, 0) + (right?.let { r.membersNamed(it, name, 0) }.orEmpty())).distinctBy { it.id ?: it.toString() }
            .filterIsInstance<CSharpSymbol.LibraryMember>().filter { it.member.kind == IndexedMemberKind.OPERATOR && r.session.parameters(it.member).size == (if (right == null) 1 else 2) }
        val operands = listOfNotNull(left, right)
        val fitting = candidates.filter { candidate ->
            r.session.parameters(candidate.member).zip(operands).all { (p, t) -> r.fromRef(p.typeRef, candidate.declaringArguments)?.let { r.conversion(t, it) } in OK }
        }
        val exact = fitting.filter { candidate -> r.session.parameters(candidate.member).zip(operands).all { (p, t) -> r.fromRef(p.typeRef, candidate.declaringArguments)?.let { r.conversion(t, it) } == CSharpNameResolver.Conversion.IDENTITY } }
        val chosen = exact.singleOrNull() ?: fitting.singleOrNull() ?: return null
        return r.fromRef(chosen.member.typeRef, chosen.declaringArguments)
    }

    // ---- several branches

    /** `c ? a : b`: the type both branches have, or the one the other converts to; a `null` / `default` / `throw` branch takes the other's. */
    private fun conditional(whenTrue: CSharpExpression?, whenFalse: CSharpExpression?): SemanticType? = common(listOfNotNull(whenTrue, whenFalse))

    /** The best common type of [expressions] (C# §12.6.3.15, the simple part); target-typed ones (`null`, `default`, `throw`) take the others'. */
    private fun common(expressions: List<CSharpExpression>): SemanticType? {
        val typed = expressions.filter { !isTargetTyped(it) }
        if (typed.isEmpty()) return null
        val types = typed.map { r.typeOf(it) ?: return null }
        val nullLiteral = expressions.any { it.elementType == SyntaxKind.NullLiteralExpression }
        var best = types.first()
        for (type in types.drop(1)) {
            if (same(type, best)) continue
            best = when {
                implicitly(type, best) && !implicitly(best, type) -> best
                implicitly(best, type) && !implicitly(type, best) -> type
                else -> return null
            }
        }
        if (nullLiteral && r.isValueType(best) && !isNullable(best)) return null
        return best
    }

    private fun same(a: SemanticType, b: SemanticType): Boolean {
        val shown = a.display
        return if (shown != null) shown == b.display else r.definitionName(a) != null && r.definitionName(a) == r.definitionName(b)
    }

    private fun implicitly(from: SemanticType, to: SemanticType): Boolean = r.conversion(from, to) == CSharpNameResolver.Conversion.IMPLICIT

    private fun tuple(e: CSharpTupleExpression): SemanticType? {
        val arguments = e.arguments
        if (arguments.size !in 2..7) return null
        val types = arguments.map { a -> a.expression?.takeIf { !isTargetTyped(it) && it !is CSharpAnonymousFunctionExpression }?.let(r::typeOf) ?: return null }
        val explicit = arguments.map { a -> a.nameColon?.nameElement?.identifier?.text }
        val inferred = arguments.map { a -> inferredName(a.expression) }
        // an inferred name that two elements would share names neither (C# 7.1)
        val names = arguments.indices.map { i -> explicit[i] ?: inferred[i]?.takeIf { name -> inferred.count { it == name } == 1 && name !in explicit } }
        val tuple = r.libraryType("System.ValueTuple`${arguments.size}") ?: return null
        return SemanticType.Library(tuple.type, types, names.takeIf { list -> list.any { it != null } })
    }

    /** C# 7.1 tuple names: `(x, o.Name)` names its elements `x` and `Name` (not `Item1`, `Rest` or `ToString`-like names). */
    private fun inferredName(e: CSharpExpression?): String? {
        val name = when (e) {
            is CSharpIdentifierName -> e.identifier?.text
            is CSharpMemberAccessExpression -> (e.nameElement as? CSharpIdentifierName)?.identifier?.text
            is CSharpConditionalAccessExpression -> ((e.whenNotNull as? CSharpMemberBindingExpression)?.nameElement as? CSharpIdentifierName)?.identifier?.text
            // `(var a, int b) = ...`: the declared variables name the elements
            is CSharpDeclarationExpression -> (e.designation as? CSharpSingleVariableDesignation)?.identifier?.text
            else -> null
        } ?: return null
        if (name in RESERVED_TUPLE_NAMES || (name.startsWith("Item") && name.drop(4).toIntOrNull() != null)) return null
        return name
    }

    companion object {
        private const val NULLABLE = "System.Nullable`1"
        private const val BOOL = "System.Boolean"
        private const val STRING = "System.String"
        private const val INT = "System.Int32"
        private const val LONG = "System.Int64"
        private const val DECIMAL = "System.Decimal"
        private val OK = setOf(CSharpNameResolver.Conversion.IDENTITY, CSharpNameResolver.Conversion.IMPLICIT)
        // the types an `int` constant converts to without the operation widening them
        private val WIDE = setOf("System.UInt32", "System.UInt64", "System.Int64", "System.IntPtr", "System.UIntPtr")
        private val NUMERIC_CONVERSIONS_TO_NATIVE = mapOf(
            "System.IntPtr" to setOf("System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Char", "System.Int32"),
            "System.UIntPtr" to setOf("System.Byte", "System.UInt16", "System.Char", "System.UInt32"),
        )
        private val SMALL = setOf("System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Char")
        private val SIGNED = setOf("System.SByte", "System.Int16", "System.Int32", "System.Int64")
        private val INTEGRAL = SMALL + setOf("System.Int32", "System.UInt32", "System.Int64", "System.UInt64", "System.IntPtr", "System.UIntPtr")
        private val NUMERIC = INTEGRAL + setOf("System.Single", "System.Double", "System.Decimal")
        private val TASKS_OF_T = setOf(
            "System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask`1", "System.Runtime.CompilerServices.ConfiguredTaskAwaitable`1",
            "System.Runtime.CompilerServices.ConfiguredValueTaskAwaitable`1",
        )
        val ARRAY_INTERFACES = setOf(
            "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
            "System.Collections.Generic.IReadOnlyCollection`1", "System.Collections.Generic.IReadOnlyList`1",
        )
        private val OPERATORS = mapOf(
            "+" to "op_Addition", "-" to "op_Subtraction", "*" to "op_Multiply", "/" to "op_Division", "%" to "op_Modulus", "&" to "op_BitwiseAnd",
            "|" to "op_BitwiseOr", "^" to "op_ExclusiveOr", "<<" to "op_LeftShift", ">>" to "op_RightShift", ">>>" to "op_UnsignedRightShift",
        )
        private val RESERVED_TUPLE_NAMES = setOf("CompareTo", "Deconstruct", "Equals", "GetHashCode", "Rest", "ToString")
    }
}
