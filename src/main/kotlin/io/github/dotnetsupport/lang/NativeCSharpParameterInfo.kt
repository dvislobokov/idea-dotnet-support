package io.github.dotnetsupport.lang

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * Parameter Info (Ctrl+P) on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3), behind [CSharpFeature.DOCUMENTATION]: every overload
 * of the called method (or constructor of `new T(...)`) as a row, as in Rider, the parameter at the caret highlighted, the overload the
 * resolver picks marked; an overload with fewer parameters than the arguments typed is greyed. With the switch on the server the handler of
 * the module `roslyn` answers and this one does not, and the other way round.
 */
class NativeCSharpParameterInfoHandler : ParameterInfoHandler<CSharpBaseArgumentList, NativeCSharpParameterInfo.Row> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): CSharpBaseArgumentList? {
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, context.file)) return null
        val file = context.file as? CSharpFile ?: return null
        val list = NativeCSharpParameterInfo.listAt(file, context.offset) ?: return null
        val rows = NativeCSharpParameterInfo.rows(file, list).ifEmpty { return null }
        context.itemsToShow = rows.toTypedArray()
        return list
    }

    override fun showParameterInfo(element: CSharpBaseArgumentList, context: CreateParameterInfoContext) = context.showHint(element, element.textRange.startOffset, this)

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): CSharpBaseArgumentList? =
        (context.file as? CSharpFile)?.let { NativeCSharpParameterInfo.listAt(it, context.offset) }

    override fun updateParameterInfo(list: CSharpBaseArgumentList, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != null && context.parameterOwner != list) return context.removeHint()
        context.parameterOwner = list
        context.setCurrentParameter(NativeCSharpParameterInfo.argumentIndex(list, context.offset))
        list.putUserData(NativeCSharpParameterInfo.NAMED, NativeCSharpParameterInfo.argumentName(list, context.offset))
        context.highlightedParameter = context.objectsToView.firstOrNull { (it as? NativeCSharpParameterInfo.Row)?.chosen == true }
    }

    override fun updateUI(row: NativeCSharpParameterInfo.Row, context: ParameterInfoUIContext) {
        val parameters = row.parameters
        val named = (context.parameterOwner as? CSharpBaseArgumentList)?.getUserData(NativeCSharpParameterInfo.NAMED)
        // `count: |`: the parameter of that name, wherever it is; an overload without one is greyed (as in Rider)
        val current = named?.let { name -> parameters.indexOfFirst { NativeCSharpParameterInfo.nameOf(it) == name }.takeIf { it >= 0 } ?: Int.MAX_VALUE } ?: context.currentParameterIndex
        if (parameters.isEmpty()) {
            context.setupUIComponentPresentation(NO_PARAMETERS, -1, -1, current > 0, false, false, context.defaultParameterColor)
            return
        }
        val range = NativeCSharpParameterInfo.rangeOf(parameters, current).takeIf { current != Int.MAX_VALUE }
        val fits = current < parameters.size || current != Int.MAX_VALUE && parameters.last().startsWith("params ")
        context.setupUIComponentPresentation(parameters.joinToString(", "), range?.first ?: -1, range?.last?.plus(1) ?: -1, !fits, false, false, context.defaultParameterColor)
    }

    private companion object {
        const val NO_PARAMETERS = "<no parameters>"
    }
}

object NativeCSharpParameterInfo {
    /** One overload: its parameters as written; [chosen]: the one the resolver picks for the call. */
    class Row(val parameters: List<String>, val chosen: Boolean) {
        override fun toString(): String = parameters.joinToString(", ", "(", ")") + if (chosen) " *" else ""
    }

    /** The argument list of a call or creation whose parentheses hold [offset]. */
    fun listAt(file: PsiFile, offset: Int): CSharpBaseArgumentList? {
        var element: PsiElement? = file.findElementAt(offset) ?: file.findElementAt(offset - 1)
        while (element != null && element !is PsiFile) {
            if (element is CSharpArgumentList && (element.parent is CSharpInvocationExpression || element.parent is CSharpBaseObjectCreationExpression ||
                    element.parent is CSharpConstructorInitializer || element.parent is CSharpPrimaryConstructorBaseType)) {
                val open = element.openParenToken ?: return null
                val close = element.closeParenToken?.takeIf { it.textLength > 0 }
                if (offset > open.textRange.startOffset && (close == null || offset <= close.textRange.startOffset)) return element
            }
            if (element is CSharpStatement || element is CSharpMemberDeclaration) return null
            element = element.parent
        }
        return null
    }

    /** Which argument the caret is in: the commas of the list before it. */
    fun argumentIndex(list: CSharpBaseArgumentList, offset: Int): Int = list.argumentsSeparators.count { it.textRange.startOffset < offset }

    /** The name of the named argument `name: value` the caret is in, null for a positional one. */
    val NAMED: Key<String> = Key.create("dotnet.parameterInfo.named")

    fun argumentName(list: CSharpBaseArgumentList, offset: Int): String? =
        list.arguments.getOrNull(argumentIndex(list, offset))?.nameColon?.nameElement?.identifier?.text

    /** `int count = 1` -> `count`, `params string[] items` -> `items`. */
    fun nameOf(parameter: String): String = parameter.substringBefore(" = ").trim().substringAfterLast(' ')

    fun rows(file: CSharpFile, list: CSharpBaseArgumentList): List<Row> {
        if (DumbService.isDumb(file.project)) return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val text = CSharpSymbolText(resolver)
        return when (val owner = list.parent) {
            is CSharpInvocationExpression -> call(owner, resolver, text)
            is CSharpBaseObjectCreationExpression -> creation(owner, resolver, text)
            // `: base(|)` / `: this(|)` of a constructor, `class B(int x) : A(|)`: the constructors of the base or of the type itself
            is CSharpConstructorInitializer -> initializer(list, owner.thisOrBaseKeyword?.text == "this", resolver, text)
            is CSharpPrimaryConstructorBaseType -> initializer(list, false, resolver, text)
            else -> emptyList()
        }
    }

    private fun call(call: CSharpInvocationExpression, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> {
        val (overloads, chosen) = overloads(call, resolver) ?: return emptyList()
        val reduced = call.expression !is CSharpSimpleName && overloads.firstOrNull()?.let(resolver::isExtension) == true
        val callee = callee(call)
        return overloads.mapNotNull { symbol -> text.parameters(symbol, reduced, methodArguments(symbol, call, callee, resolver))?.let { Row(it, symbol == chosen) } }
    }

    /** What the receiver and the arguments written so far fix of a generic method's type parameters: `Func<Order, bool> predicate`, not `Func<TSource, bool>`. */
    private fun methodArguments(symbol: CSharpSymbol, call: CSharpInvocationExpression, callee: CSharpSimpleName?, resolver: CSharpNameResolver): List<SemanticType?> {
        if (callee == null || symbol !is CSharpSymbol.LibraryMember || symbol.member.arity == 0) return emptyList()
        return runCatching { resolver.expressions.typeArguments(symbol, call, callee, withLambdas = false) }.getOrDefault(emptyList())
    }

    private fun callee(call: CSharpInvocationExpression): CSharpSimpleName? = when (val expression = call.expression) {
        is CSharpSimpleName -> expression
        is CSharpMemberAccessExpression -> expression.nameElement
        is CSharpMemberBindingExpression -> expression.nameElement
        else -> null
    }

    /** Every overload of the method [call] calls, and the one the resolver picks (null when it cannot pick one). */
    internal fun overloads(call: CSharpInvocationExpression, resolver: CSharpNameResolver): Pair<List<CSharpSymbol>, CSharpSymbol?>? {
        val callee = callee(call) ?: return null
        val resolution = resolver.resolveName(callee) ?: return null
        return overloadsOf(resolution.symbols.first(), resolver).ifEmpty { resolution.symbols } to resolution.single
    }

    /** What an argument list of [owner] (an invocation or a creation) may go to: the overloads, the one the resolver picks first. */
    internal fun candidates(owner: PsiElement, resolver: CSharpNameResolver): List<CSharpSymbol> = when (owner) {
        is CSharpInvocationExpression -> overloads(owner, resolver)?.let { (all, chosen) -> listOfNotNull(chosen) + all.filter { it != chosen } }.orEmpty()
        is CSharpBaseObjectCreationExpression -> constructors(owner, resolver).let { all ->
            val chosen = resolver.pickConstructor(all, owner.argumentList?.arguments.orEmpty())
            listOfNotNull(chosen) + all.filter { it != chosen }
        }
        else -> emptyList()
    }

    /** Every method of the name of [symbol] where it is declared: the type's (with the inherited ones of an assembly), the static class's of an extension. */
    private fun overloadsOf(symbol: CSharpSymbol, resolver: CSharpNameResolver): List<CSharpSymbol> = when (symbol) {
        is CSharpSymbol.LibraryMember -> {
            val member = symbol.member
            if (member.kind == IndexedMemberKind.EXTENSION_METHOD) member.type.members.filter { it.name == member.name && it.kind == member.kind }.map { CSharpSymbol.LibraryMember(it) }
            else resolver.session.libraryMembers(resolver.assemblies, member.type)[member.name].orEmpty().filter { it.member.kind.isCallable }
                .map { CSharpSymbol.LibraryMember(it.member, if (it.from == null) symbol.declaringArguments else emptyList()) }
        }
        is CSharpSymbol.SourceMember -> {
            val method = symbol.element as? CSharpMethodDeclaration
            val type = method?.parent as? CSharpBaseTypeDeclaration
            val name = method?.identifier?.text
            val info = type?.let(resolver.syntax::declaredType)
            if (info == null || name == null) listOf(symbol)
            else resolver.syntax.membersOf(info)[name]?.namedTargets().orEmpty().filterIsInstance<CSharpMethodDeclaration>().map { CSharpSymbol.SourceMember(it, symbol.member, symbol.owner) }
        }
        else -> listOf(symbol)
    }

    /** The constructor the arguments of [list] (of `new`, `: this(…)` / `: base(…)`, a primary constructor's base) pick; null when the resolver cannot pick one. */
    internal fun chosenConstructor(list: CSharpBaseArgumentList, resolver: CSharpNameResolver): CSharpSymbol? {
        val type = when (val owner = list.parent) {
            is CSharpBaseObjectCreationExpression -> resolver.typeOf(owner)
            is CSharpConstructorInitializer, is CSharpPrimaryConstructorBaseType -> {
                val declaration = PsiTreeUtil.getParentOfType(list, CSharpTypeDeclaration::class.java) ?: return null
                val own = resolver.selfType(resolver.syntax.declaredType(declaration) ?: return null)
                if ((owner as? CSharpConstructorInitializer)?.thisOrBaseKeyword?.text == "this") own else resolver.baseTypes(own).firstOrNull { !NativeCSharpGenerate.isInterface(it) }
            }
            else -> null
        } ?: return null
        return resolver.pickConstructor(constructorsOf(type), list.arguments)
    }

    private fun creation(creation: CSharpBaseObjectCreationExpression, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> =
        constructorRows(resolver.typeOf(creation) ?: return emptyList(), creation.argumentList?.arguments.orEmpty(), resolver, text)

    private fun initializer(list: CSharpBaseArgumentList, self: Boolean, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> {
        val declaration = PsiTreeUtil.getParentOfType(list, CSharpTypeDeclaration::class.java) ?: return emptyList()
        val own = resolver.selfType(resolver.syntax.declaredType(declaration) ?: return emptyList())
        val type = if (self) own else resolver.baseTypes(own).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: return emptyList()
        return constructorRows(type, list.arguments, resolver, text)
    }

    private fun constructors(creation: CSharpBaseObjectCreationExpression, resolver: CSharpNameResolver): List<CSharpSymbol> =
        constructorsOf(resolver.typeOf(creation) ?: return emptyList())

    private fun constructorRows(type: SemanticType, arguments: List<CSharpArgument>, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> {
        val constructors = constructorsOf(type)
        // the one the arguments pick, as the server marks it (robot, E-84: `new StringBuilder(16)` marked none)
        val chosen = resolver.pickConstructor(constructors, arguments)
        return constructors.map { Row(text.parameters(it, false).orEmpty(), it == chosen) }
    }

    private fun constructorsOf(type: SemanticType): List<CSharpSymbol> {
        return when (type) {
            is SemanticType.Library -> type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden }.map { CSharpSymbol.LibraryMember(it, type.arguments) }
            is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.flatMap { declaration ->
                val explicit = declaration.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }
                (explicit + listOfNotNull(declaration.takeIf { it.parameterList != null })).map { CSharpSymbol.SourceMember(it, Member.method(emptyList(), false).at { it }, type) }
            }.ifEmpty { listOf(CSharpSymbol.SourceMember(type.info.parts.first().element() ?: return emptyList(), Member.method(emptyList(), false), type)) }
            else -> emptyList()
        }
    }

    /** Where the parameter [index] is in `parameters.joinToString(", ")`; the last one for a `params` array the caret has gone past. */
    fun rangeOf(parameters: List<String>, index: Int): IntRange? {
        val target = when {
            index < 0 -> return null
            index in parameters.indices -> index
            parameters.isNotEmpty() && parameters.last().startsWith("params ") -> parameters.lastIndex
            else -> return null
        }
        val start = parameters.take(target).sumOf { it.length + 2 }
        return start until start + parameters[target].length
    }
}
