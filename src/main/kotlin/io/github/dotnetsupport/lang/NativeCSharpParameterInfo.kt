package io.github.dotnetsupport.lang

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, context.project)) return null
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
            if (element is CSharpArgumentList && (element.parent is CSharpInvocationExpression || element.parent is CSharpBaseObjectCreationExpression)) {
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
            else -> emptyList()
        }
    }

    private fun call(call: CSharpInvocationExpression, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> {
        val callee = when (val expression = call.expression) {
            is CSharpSimpleName -> expression
            is CSharpMemberAccessExpression -> expression.nameElement
            is CSharpMemberBindingExpression -> expression.nameElement
            else -> null
        } ?: return emptyList()
        val resolution = resolver.resolveName(callee) ?: return emptyList()
        val chosen = resolution.single
        val first = resolution.symbols.first()
        val reduced = call.expression !is CSharpSimpleName && resolver.isExtension(first)
        val overloads = overloadsOf(first, resolver).ifEmpty { resolution.symbols }
        return overloads.mapNotNull { symbol -> text.parameters(symbol, reduced)?.let { Row(it, symbol == chosen) } }
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
            else resolver.syntax.membersOf(info)[name]?.targets().orEmpty().filterIsInstance<CSharpMethodDeclaration>().map { CSharpSymbol.SourceMember(it, symbol.member, symbol.owner) }
        }
        else -> listOf(symbol)
    }

    private fun creation(creation: CSharpBaseObjectCreationExpression, resolver: CSharpNameResolver, text: CSharpSymbolText): List<Row> {
        val type = resolver.typeOf(creation) ?: return emptyList()
        val constructors: List<CSharpSymbol> = when (type) {
            is SemanticType.Library -> type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden }.map { CSharpSymbol.LibraryMember(it, type.arguments) }
            is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.flatMap { declaration ->
                val explicit = declaration.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }
                (explicit + listOfNotNull(declaration.takeIf { it.parameterList != null })).map { CSharpSymbol.SourceMember(it, Member.method(emptyList(), false).at { it }, type) }
            }.ifEmpty { listOf(CSharpSymbol.SourceMember(type.info.parts.first().element() ?: return emptyList(), Member.method(emptyList(), false), type)) }
            else -> return emptyList()
        }
        // the one the arguments pick, as the server marks it (robot, E-84: `new StringBuilder(16)` marked none)
        val chosen = resolver.pickConstructor(constructors, creation.argumentList?.arguments.orEmpty())
        return constructors.map { Row(text.parameters(it, false).orEmpty(), it == chosen) }
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
