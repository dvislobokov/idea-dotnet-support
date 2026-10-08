package io.github.dotnetsupport.lang

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpTypeFacts
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.settings.DotNetSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * Inlay hints of C# on the plugin's own semantics (INLAY_HINTS = Built-in): the names of parameters before arguments and the types of
 * `var`, of lambda parameters, of `new()` and of collection expressions, with the rules of Roslyn's `InlineParameterNameHintsService` /
 * `InlineTypeHintsService` and the thirteen options of Settings | .NET | Language Server, so the switch changes who answers and nothing
 * else. Parameters come from the overload resolution of the call (what parameter info marks), types from [CSharpNameResolver]; no hint
 * where either is unsure (never an error type, never a guess between overloads). Pure over the tree: the providers below only render.
 */
object NativeCSharpInlayHints {
    enum class Kind { PARAMETER, TYPE }

    /** Where a click on a type hint goes: a declaration of the solution, or a type of an assembly by its full name (resolved on the click). */
    sealed class Target {
        class Source(val element: PsiElement) : Target()
        class Library(val fullName: String) : Target()
    }

    /** One hint: [text] shown before the character at [offset] (`name:` of a parameter, the type); [after]: it belongs to the token before it (`new ⟨T⟩()`). */
    class Hint(val offset: Int, val text: String, val kind: Kind, val target: Target? = null, val after: Boolean = false) {
        override fun toString(): String = "$offset:$text"
    }

    /** The options of the settings page, read once per pass. [parameters] / [types] are the master toggles of their group. */
    class Options(
        val parameters: Boolean = true, val literals: Boolean = true, val indexers: Boolean = true, val objectCreation: Boolean = true, val others: Boolean = true,
        val suppressSuffix: Boolean = true, val suppressIntent: Boolean = true, val suppressArgumentName: Boolean = true,
        val types: Boolean = true, val varTypes: Boolean = true, val lambdaTypes: Boolean = true, val implicitNew: Boolean = true, val collections: Boolean = true,
        /** No type after `var` when the initializer says it (`new T()`, a literal, a cast, an enum member…), as Rider's «Hide hints for obvious types»; plugin setting, not the server's. */
        val hideObvious: Boolean = true,
    ) {
        /** Any hint of [kind] can come out of these options. */
        fun any(kind: Kind): Boolean = when (kind) {
            Kind.PARAMETER -> parameters && (literals || indexers || objectCreation || others)
            Kind.TYPE -> types && (varTypes || lambdaTypes || implicitNew || collections)
        }

        companion object {
            /** The values of Settings | .NET | Language Server (the same the server gets): the switch changes who answers, not what. */
            fun fromSettings(): Options {
                fun on(section: String): Boolean = RoslynOptions.isOn(section)
                return Options(
                    parameters = on("inlay_hints.dotnet_enable_inlay_hints_for_parameters"), literals = on("inlay_hints.dotnet_enable_inlay_hints_for_literal_parameters"),
                    indexers = on("inlay_hints.dotnet_enable_inlay_hints_for_indexer_parameters"), objectCreation = on("inlay_hints.dotnet_enable_inlay_hints_for_object_creation_parameters"),
                    others = on("inlay_hints.dotnet_enable_inlay_hints_for_other_parameters"), suppressSuffix = on("inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_differ_only_by_suffix"),
                    suppressIntent = on("inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_method_intent"), suppressArgumentName = on("inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_argument_name"),
                    types = on("inlay_hints.csharp_enable_inlay_hints_for_types"), varTypes = on("inlay_hints.csharp_enable_inlay_hints_for_implicit_variable_types"),
                    lambdaTypes = on("inlay_hints.csharp_enable_inlay_hints_for_lambda_parameter_types"), implicitNew = on("inlay_hints.csharp_enable_inlay_hints_for_implicit_object_creation"),
                    collections = on("inlay_hints.csharp_enable_inlay_hints_for_collection_expressions"), hideObvious = DotNetSettings.getInstance().hideObviousTypeHints,
                )
            }
        }
    }

    /** The file is the native tree's, the switch gives INLAY_HINTS to the plugin and the indexes are ready (settings and dumb mode only, no PSI). */
    fun serves(file: PsiFile?): Boolean =
        file is CSharpFile && file.compilationUnit != null && !DumbService.isDumb(file.project) && CSharpFeatures.native(CSharpFeature.INLAY_HINTS, file.project)

    /** Every hint of [file], in the order of the text (tests and the whole-file callers). */
    fun hints(file: CSharpFile, options: Options, resolver: CSharpNameResolver = CSharpSemanticSession(file.project).resolver(file)): List<Hint> {
        val found = ArrayList<Hint>()
        com.intellij.psi.SyntaxTraverser.psiTraverser(file).forEach { found += hintsOf(it, options, resolver) }
        return found.sortedBy { it.offset }
    }

    /** The hints [element] itself carries (an argument, a declaration, a lambda parameter, `new()`, `[…]`); the tree is walked by the caller. */
    fun hintsOf(element: PsiElement, options: Options, resolver: CSharpNameResolver): List<Hint> = when (element) {
        is CSharpArgument -> listOfNotNull(if (options.parameters) parameterHint(element, options, resolver) else null)
        is CSharpVariableDeclaration -> listOfNotNull(if (options.types && options.varTypes) varHint(element, options, resolver) else null)
        is CSharpForEachStatement -> listOfNotNull(if (options.types && options.varTypes) forEachHint(element, resolver) else null)
        is CSharpDeclarationExpression -> listOfNotNull(if (options.types && options.varTypes) declarationHint(element, resolver) else null)
        is CSharpSingleVariableDesignation -> listOfNotNull(if (options.types && options.varTypes) deconstructionHint(element, resolver) else null)
        is CSharpParameter -> listOfNotNull(if (options.types && options.lambdaTypes) lambdaHint(element, resolver) else null)
        is CSharpImplicitObjectCreationExpression -> listOfNotNull(if (options.types && options.implicitNew) implicitNewHint(element, resolver) else null)
        is CSharpCollectionExpression -> listOfNotNull(if (options.types && options.collections) collectionHint(element, resolver) else null)
        else -> emptyList()
    }

    // ---- parameter names (Roslyn: AbstractInlineParameterNameHintsService + CSharpInlineParameterNameHintsService)

    private enum class ArgumentKind { LITERAL, OBJECT_CREATION, OTHER }

    /** The callee an argument list goes to: its name (the method's; a type's for a constructor), whether it is an ordinary method, its parameters as the call sees them. */
    private class Callee(val name: String, val isMethod: Boolean, val parameters: List<CSharpNameResolver.Parameter>)

    private fun parameterHint(argument: CSharpArgument, options: Options, resolver: CSharpNameResolver): Hint? {
        // a named argument needs no hint; `__arglist` and the like have no expression
        if (argument.nameColon != null) return null
        val expression = argument.expression ?: return null
        val list = argument.parent as? CSharpBaseArgumentList ?: return null
        val owner = list.parent ?: return null
        val indexer = owner is CSharpElementAccessExpression || owner is CSharpElementBindingExpression
        if (indexer && !options.indexers) return null
        val kind = kindOf(expression)
        val wanted = when (kind) {
            ArgumentKind.LITERAL -> options.literals
            ArgumentKind.OBJECT_CREATION -> options.objectCreation
            ArgumentKind.OTHER -> options.others
        }
        if (!wanted) return null
        val callee = calleeOf(list, owner, resolver) ?: return null
        val index = list.arguments.indexOf(argument)
        // Roslyn's DetermineParameter(allowParamArray: false): the arguments of a `params` array get none
        val parameter = callee.parameters.getOrNull(index)?.takeIf { !it.isParams && it.name.isNotEmpty() } ?: return null
        if (options.suppressSuffix && differOnlyBySuffix(callee.parameters.map { it.name })) return null
        if (options.suppressIntent && matchesMethodIntent(callee, parameter)) return null
        if (options.suppressArgumentName && matchesArgumentName(expression, parameter.name)) return null
        return Hint(argument.textRange.startOffset, parameter.name + ":", Kind.PARAMETER)
    }

    /** Roslyn's GetKind: a literal through casts, prefix operators and `!`; `new`; everything else. */
    private fun kindOf(e: CSharpExpression?): ArgumentKind = when {
        e is CSharpLiteralExpression || e is CSharpInterpolatedStringExpression -> ArgumentKind.LITERAL
        e is CSharpBaseObjectCreationExpression -> ArgumentKind.OBJECT_CREATION
        e is CSharpCastExpression -> kindOf(e.expression)
        e is CSharpPrefixUnaryExpression -> kindOf(e.operand)
        e is CSharpPostfixUnaryExpression && e.elementType == SyntaxKind.SuppressNullableWarningExpression -> kindOf(e.operand)
        else -> ArgumentKind.OTHER
    }

    private fun calleeOf(list: CSharpBaseArgumentList, owner: PsiElement, resolver: CSharpNameResolver): Callee? = when (owner) {
        is CSharpInvocationExpression -> {
            val callee = (owner.expression as? CSharpSimpleName) ?: (owner.expression as? CSharpMemberAccessExpression)?.nameElement ?: (owner.expression as? CSharpMemberBindingExpression)?.nameElement
            val symbol = NativeCSharpParameterInfo.overloads(owner, resolver)?.second
            if (callee == null || symbol == null) null
            else resolver.signature(symbol, resolver.expressions.isReduced(symbol, callee))?.let { Callee(nameOf(symbol) ?: callee.identifier?.text.orEmpty(), isMethod(symbol), it) }
        }
        is CSharpBaseObjectCreationExpression, is CSharpConstructorInitializer, is CSharpPrimaryConstructorBaseType -> {
            val symbol = NativeCSharpParameterInfo.chosenConstructor(list, resolver)
            symbol?.let { resolver.signature(it, false) }?.let { Callee(nameOf(symbol).orEmpty(), false, it) }
        }
        is CSharpElementAccessExpression -> owner.expression?.let(resolver::typeOf)?.let { resolver.expressions.indexerParameters(it, list.arguments) }?.let { Callee("this", false, it) }
        is CSharpElementBindingExpression -> (owner.parent as? CSharpConditionalAccessExpression)?.expression?.let(resolver::typeOf)?.let(resolver::unwrapNullable)
            ?.let { resolver.expressions.indexerParameters(it, list.arguments) }?.let { Callee("this", false, it) }
        else -> null
    }

    private fun nameOf(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.LibraryMember -> if (symbol.member.kind == IndexedMemberKind.CONSTRUCTOR) symbol.member.type.simpleName else symbol.member.name
        is CSharpSymbol.SourceMember -> when (val e = symbol.element) {
            is CSharpMethodDeclaration -> e.identifier?.text
            is CSharpLocalFunctionStatement -> e.identifier?.text
            is CSharpBaseTypeDeclaration -> e.identifier?.text
            is CSharpConstructorDeclaration -> (e.parent as? CSharpBaseTypeDeclaration)?.identifier?.text
            else -> null
        }
        else -> null
    }

    /** An ordinary method, not a constructor: the only kind the intent rule (`SetColor(color)`) is about. */
    private fun isMethod(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.METHOD || symbol.member.kind == IndexedMemberKind.EXTENSION_METHOD
        is CSharpSymbol.SourceMember -> symbol.element is CSharpMethodDeclaration || symbol.element is CSharpLocalFunctionStatement
        else -> false
    }

    /** Roslyn's ParametersDifferOnlyBySuffix: `(a1, a2)` or `(xA, xB)` — every parameter, two or more of them. */
    fun differOnlyBySuffix(names: List<String>): Boolean {
        if (names.size <= 1) return false
        val alpha = names.all { it.length >= 2 && it.last().isLetter() } && names.map { it.dropLast(1) }.distinct().size == 1
        val numeric = names.all { it.last().isDigit() } && names.map { it.trimEnd { c -> c.isDigit() } }.distinct().let { prefixes -> prefixes.size == 1 && prefixes.single().isNotEmpty() }
        return alpha || numeric
    }

    /** Roslyn's MatchesMethodIntent: the only parameter of `EnableX(bool)` / `DisableX(bool)` / `SetColor(color)`. */
    private fun matchesMethodIntent(callee: Callee, parameter: CSharpNameResolver.Parameter): Boolean {
        if (!callee.isMethod || callee.parameters.size != 1) return false
        return matchesMethodIntent(callee.name, parameter.name) { (parameter.type() as? SemanticType.Library)?.fullName == "System.Boolean" }
    }

    fun matchesMethodIntent(methodName: String, parameterName: String, isBoolean: () -> Boolean): Boolean {
        if (suffixOf("Enable", methodName) != null || suffixOf("Disable", methodName) != null) return isBoolean()
        val suffix = suffixOf("Set", methodName) ?: return false
        return suffix.length == parameterName.length && suffix[0].lowercaseChar() == parameterName[0].lowercaseChar() && suffix.regionMatches(1, parameterName, 1, suffix.length - 1)
    }

    private fun suffixOf(prefix: String, name: String): String? = if (name.length > prefix.length && name.startsWith(prefix)) name.substring(prefix.length) else null

    /** Roslyn's MatchesParameterName: the argument is a name, or ends with one (`this.name`, `order.name`), that is the parameter's. */
    private fun matchesArgumentName(expression: CSharpExpression, parameterName: String): Boolean {
        val name = when (expression) {
            is CSharpIdentifierName -> expression.identifier?.text
            is CSharpMemberAccessExpression -> (expression.nameElement as? CSharpIdentifierName)?.identifier?.text
            else -> null
        } ?: return false
        return name.removePrefix("@").equals(parameterName, ignoreCase = true)
    }

    // ---- types (Roslyn: CSharpInlineTypeHintsService)

    private fun isVar(type: CSharpType?): Boolean = type is CSharpIdentifierName && type.identifier?.text == "var"

    /** `var x = …` (also `using var`, `for (var …`): the type before the name, as the place writes it. */
    private fun varHint(declaration: CSharpVariableDeclaration, options: Options, resolver: CSharpNameResolver): Hint? {
        if (!isVar(declaration.type)) return null
        val variable = declaration.variables.singleOrNull() ?: return null
        if (options.hideObvious && isObvious(variable.initializer?.value, resolver)) return null
        return localHint(variable.identifier ?: return null, resolver)
    }

    /**
     * The initializer names the type by itself: `new T(…)` with the type written (not `new()`), a literal, `default(T)`, `typeof(T)`, `(T)x`, `x as T`,
     * a member of an enum (`Color.Green`) or `nameof(…)`. Calls, queries, `await` and other member accesses are not: the type is the news there.
     */
    internal fun isObvious(value: CSharpExpression?, resolver: CSharpNameResolver): Boolean = when (value) {
        null -> false
        is CSharpParenthesizedExpression -> isObvious(value.expression, resolver)
        is CSharpObjectCreationExpression, is CSharpArrayCreationExpression, is CSharpCastExpression, is CSharpTypeOfExpression, is CSharpInterpolatedStringExpression -> true
        is CSharpDefaultExpression -> true
        is CSharpLiteralExpression -> value.text != "null" && value.text != "default"
        is CSharpPrefixUnaryExpression -> value.operatorToken?.text.let { it == "-" || it == "+" } && value.operand is CSharpLiteralExpression
        is CSharpBinaryExpression -> value.operatorToken?.text == "as"
        is CSharpInvocationExpression -> (value.expression as? CSharpIdentifierName)?.identifier?.text == "nameof"
        is CSharpMemberAccessExpression -> (value.nameElement as? CSharpSimpleName)?.let { name ->
            when (val symbol = resolver.resolveName(name)?.single) {
                is CSharpSymbol.SourceMember -> symbol.element is CSharpEnumMemberDeclaration
                is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
                else -> false
            }
        } == true
        else -> false
    }

    private fun forEachHint(statement: CSharpForEachStatement, resolver: CSharpNameResolver): Hint? =
        if (isVar(statement.type)) statement.identifier?.let { localHint(it, resolver) } else null

    /** `out var x`, `(var a, var b)`: the designation of a `var` declaration expression. */
    private fun declarationHint(declaration: CSharpDeclarationExpression, resolver: CSharpNameResolver): Hint? {
        if (!isVar(declaration.type)) return null
        val designation = declaration.designation as? CSharpSingleVariableDesignation ?: return null
        return localHint(designation.identifier ?: return null, resolver)
    }

    /** `var (a, b) = …`, `foreach (var (a, b) in …)`: each name of the parenthesized designation of a `var` declaration. */
    private fun deconstructionHint(designation: CSharpSingleVariableDesignation, resolver: CSharpNameResolver): Hint? {
        if (designation.parent !is CSharpParenthesizedVariableDesignation) return null
        var at: PsiElement = designation.parent
        while (at is CSharpParenthesizedVariableDesignation) at = at.parent ?: return null
        if (at !is CSharpDeclarationExpression || !isVar(at.type)) return null
        return localHint(designation.identifier ?: return null, resolver)
    }

    /** The type of the local declared by [identifier] — by the one resolver of locals, so `var`, `foreach`, `out var` and deconstruction agree with Go to Declaration. */
    private fun localHint(identifier: PsiElement, resolver: CSharpNameResolver): Hint? {
        val local = resolver.syntax.symbolAt(identifier)?.takeIf { it.declaration == identifier } ?: return null
        return typeHint(identifier.textRange.startOffset, resolver.localType(CSharpSymbol.Local(local)), identifier, resolver)
    }

    /** A lambda parameter without a type: the one the delegate gives it. */
    private fun lambdaHint(parameter: CSharpParameter, resolver: CSharpNameResolver): Hint? {
        if (parameter.type != null) return null
        val parent = parameter.parent
        val lambda = parent is CSharpSimpleLambdaExpression || parent is CSharpParameterList && parent.parent is CSharpParenthesizedLambdaExpression
        if (!lambda) return null
        val identifier = parameter.identifier ?: return null
        return typeHint(identifier.textRange.startOffset, resolver.lambdaParameterType(parameter), identifier, resolver)
    }

    /** `new ⟨T⟩(…)`: the type `new()` stands for, after the keyword. */
    private fun implicitNewHint(creation: CSharpImplicitObjectCreationExpression, resolver: CSharpNameResolver): Hint? {
        val keyword = creation.newKeyword ?: return null
        return typeHint(keyword.textRange.endOffset, resolver.typeOf(creation), creation, resolver, after = true)
    }

    /** `⟨List<int>⟩ [1, 2]`: the type the collection expression converts to. */
    private fun collectionHint(collection: CSharpCollectionExpression, resolver: CSharpNameResolver): Hint? =
        typeHint(collection.textRange.startOffset, resolver.expressions.target(collection), collection, resolver)

    /** No hint for a type a part of which is unknown (Roslyn's IsValidType: never an error type), nor for `void`. */
    private fun typeHint(offset: Int, type: SemanticType?, at: PsiElement, resolver: CSharpNameResolver, after: Boolean = false): Hint? {
        if (type == null) return null
        val written = CSharpTypeFacts.written(resolver, type, at) ?: return null
        if (written == "void" || written == "var" || written.isEmpty()) return null
        return Hint(offset, written, Kind.TYPE, targetOf(type), after)
    }

    private fun targetOf(type: SemanticType): Target? = when (type) {
        is SemanticType.Source -> type.info.targets().firstOrNull()?.let { Target.Source(it) }
        is SemanticType.Library -> Target.Library(type.type.fullName)
        is SemanticType.ArrayOf -> type.element?.let(::targetOf)
        is SemanticType.Parameter -> null
    }
}

/**
 * The declarative inlay providers of C# (Settings | Editor | Inlay Hints | C#): one for the parameter names, one for the types, as the
 * platform groups them. Silent while the IDE indexes and while the language server serves INLAY_HINTS (`RoslynLspIntegration` disables
 * the server's hints when the plugin's are on), so one source draws at a time.
 */
abstract class NativeCSharpInlayHintsProvider(private val kind: NativeCSharpInlayHints.Kind) : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!NativeCSharpInlayHints.serves(file)) return null
        val options = NativeCSharpInlayHints.Options.fromSettings()
        if (!options.any(kind)) return null
        val format = if (kind == NativeCSharpInlayHints.Kind.PARAMETER) HintFormat.default.withColorKind(HintColorKind.Parameter) else HintFormat.default
        return object : SharedBypassCollector {
            // the resolver of the pass, made on the first element: createCollector may run outside the pass's read action
            private val resolver by lazy { CSharpSemanticSession(file.project).resolver(file as CSharpFile) }

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val hints = try {
                    NativeCSharpInlayHints.hintsOf(element, options, resolver).filter { it.kind == kind }
                } catch (_: IndexNotReadyException) {
                    return
                }
                for (hint in hints) {
                    val action = when (val target = hint.target) {
                        is NativeCSharpInlayHints.Target.Source -> InlayActionData(PsiPointerInlayActionPayload(SmartPointerManager.createPointer(target.element)), PsiPointerInlayActionNavigationHandler.HANDLER_ID)
                        is NativeCSharpInlayHints.Target.Library -> InlayActionData(StringInlayActionPayload(target.fullName), NativeCSharpLibraryTypeHintHandler.ID)
                        null -> null
                    }
                    sink.addPresentation(InlineInlayPosition(hint.offset, relatedToPrevious = hint.after), hintFormat = format) { text(hint.text, action) }
                }
            }
        }
    }
}

class NativeCSharpParameterHintsProvider : NativeCSharpInlayHintsProvider(NativeCSharpInlayHints.Kind.PARAMETER)

class NativeCSharpTypeHintsProvider : NativeCSharpInlayHintsProvider(NativeCSharpInlayHints.Kind.TYPE)

/**
 * The hints on screen follow the page at once. The inlay passes of the platform run again only when the PSI changed, so an Apply that
 * changes an «Inlay Hints» option or the source of «Inlay hints» changed nothing until the next edit (robot, 0.1.117): when the answer
 * of either changes, the declarative pass is forced again in every editor of a C# file (the plugin's hints), and the older pass with it
 * (the server's hints, whose provider asks the switch per pass): a switch of the source while the server runs shows its hints, or
 * hides them, on that pass, no restart of the server.
 */
class NativeCSharpInlayHintsSwitch : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) {
        val now = RoslynOptions.ALL.filter { it.section.startsWith("inlay_hints.") }.map { RoslynOptions.value(it.section) } + CSharpFeatures.native(CSharpFeature.INLAY_HINTS).toString() + DotNetSettings.getInstance().hideObviousTypeHints.toString()
        if (last.getAndSet(now) == now) return
        CSharpEditorRefresh.onEdt {
            InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
            for ((editor, project) in CSharpEditorRefresh.editors()) DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
        }
    }

    private companion object {
        val last = AtomicReference<List<String>?>(null)
    }
}

/** The editors of C# files, and the EDT, for the listeners of the Language Server page that redraw what is open. */
internal object CSharpEditorRefresh {
    /** Now when on the EDT (the settings page), else on it later. */
    fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) block() else application.invokeLater(block, ModalityState.nonModal())
    }

    /** Every editor of a C# file of an open project, with its project. */
    fun editors(): List<Pair<Editor, Project>> = EditorFactory.getInstance().allEditors.mapNotNull { editor ->
        val project = editor.project?.takeIf { !it.isDisposed } ?: return@mapNotNull null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@mapNotNull null
        if (file.extension.equals("cs", ignoreCase = true)) editor to project else null
    }
}

/** A click on the type of an assembly in a type hint: its metadata view, as Go to Declaration opens it; the type is found again on the click. */
class NativeCSharpLibraryTypeHintHandler : InlayActionHandler {
    override fun handleClick(editor: Editor, payload: InlayActionPayload) {
        val fullName = (payload as? StringInlayActionPayload)?.text ?: return
        val project = editor.project ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? CSharpFile ?: return
        val type = CSharpSemanticSession(project).resolver(file).libraryType(fullName)?.type ?: return
        AssemblyNavigation.navigate(project, type, null, true)
    }

    companion object {
        const val ID = "dotnet.csharp.library.type"
    }
}
