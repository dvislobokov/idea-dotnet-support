package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * What an argument list offers in the native list (0.1.86), on the plugin's semantics where the server used to answer: a lambda where a
 * delegate is expected (`items.Where(` -> `x => `, `(x, i) => `, Rider's dumps 15b, 24), the named arguments of the overloads that fit
 * (`Place(qu` -> `quantity:`, dump 5) and the properties of an attribute (`[Obsolete(DiagnosticId = `), and at a delegate target that is no
 * argument (`Changed += `, `Func<int, bool> f = `) the lambda and «Create method» (dumps 24, 48).
 */
object NativeCSharpArgumentCompletion {
    /** Above every native item but the common calls of a task method, as the lambda of the server stood above the server's list. */
    const val LAMBDA = 200.0

    /** A named argument whose name the typed prefix starts: first, as in Rider (`Place(qu` -> `quantity:`). */
    const val NAMED_TYPED = 250.0

    /** A named argument with nothing typed: below the values, above the keywords (Rider lists them far down). */
    const val NAMED = 1.0

    fun items(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement> {
        val name = place.name ?: return emptyList()
        if (DumbService.isDumb(file.project)) return emptyList()
        val resolver by lazy { CSharpSemanticSession(file.project).resolver(file) }
        val result = ArrayList<LookupElement>()
        val text = file.viewProvider.document?.charsSequence ?: file.text
        val typed = matcher.prefix.isNotEmpty()
        runCatching {
            if (CSharpLambdaNames.atArgumentStart(text, place.offset)) {
                val indent = CSharpExpressions.indentAt(text, place.offset)
                for ((i, lambda) in NativeCSharpLambdas.at(file, place.offset, resolver).withIndex()) {
                    result += native(lambda.inlineElement(), LAMBDA - i)
                    result += native(lambda.blockElement(indent), LAMBDA - i - 0.5)
                }
            }
            when (val parent = name.parent) {
                is CSharpArgument -> if (parent.expression == name && parent.nameColon == null) {
                    for (named in namedArguments(parent, resolver)) result += native(namedElement(named), if (typed) NAMED_TYPED else NAMED)
                }
                is CSharpAttributeArgument -> if (parent.expression == name && parent.nameColon == null && parent.nameEquals == null) {
                    attributeArguments(parent, resolver).forEach { (named, property) -> result += native(if (property) propertyElement(named) else namedElement(named), if (typed) NAMED_TYPED else NAMED) }
                }
                is CSharpAssignmentExpression -> if (parent.right == name && parent.operatorToken?.text.let { it == "+=" || it == "=" }) {
                    parent.left?.let { left -> delegateTarget(left, resolver.typeOf(left), name, text, resolver, result) }
                }
                is CSharpEqualsValueClause -> {
                    val declarator = parent.parent as? CSharpVariableDeclarator
                    val type = (declarator?.parent as? CSharpVariableDeclaration)?.type?.takeIf { it.text != "var" }?.let(resolver::resolveType)
                    if (declarator != null) delegateTarget(declarator, type, name, text, resolver, result)
                }
            }
        }.onFailure { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it }
        return result
    }

    private fun native(element: LookupElementBuilder, priority: Double): LookupElement {
        element.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(element, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    // ---- named arguments

    /** `quantity:` -> `quantity: `. */
    private fun namedElement(name: String): LookupElementBuilder = LookupElementBuilder.create("$name:").withIcon(AllIcons.Nodes.Parameter)
        .withInsertHandler { context, _ ->
            val document = context.document
            val tail = context.tailOffset
            if (document.charsSequence.getOrNull(tail) != ' ') document.insertString(tail, " ")
            context.editor.caretModel.moveToOffset(tail + 1)
        }

    /** `DiagnosticId` -> `DiagnosticId = `. */
    private fun propertyElement(name: String): LookupElementBuilder = LookupElementBuilder.create(name).withPresentableText("$name =").withIcon(AllIcons.Nodes.Property)
        .withInsertHandler { context, _ ->
            val tail = context.tailOffset
            context.document.insertString(tail, " = ")
            context.editor.caretModel.moveToOffset(tail + 3)
        }

    /**
     * The parameters [argument] can name: of every overload that takes as many positional arguments as are written before it and has the
     * names written in the list, from its first parameter not taken by position, the names already used left out; the chosen overload first.
     */
    fun namedArguments(argument: CSharpArgument, resolver: CSharpNameResolver): List<String> {
        val list = argument.parent as? CSharpArgumentList ?: return emptyList()
        val owner = list.parent ?: return emptyList()
        val arguments = list.arguments
        val index = arguments.indexOf(argument)
        val positional = arguments.take(index.coerceAtLeast(0)).count { it.nameColon == null }
        val used = arguments.filter { it != argument }.mapNotNullTo(HashSet()) { it.nameColon?.nameElement?.identifier?.text }
        val result = LinkedHashSet<String>()
        for (symbol in NativeCSharpParameterInfo.candidates(owner, resolver)) {
            val reduced = owner is CSharpInvocationExpression && owner.expression !is CSharpSimpleName && resolver.isExtension(symbol)
            val parameters = resolver.signature(symbol, false)?.drop(if (reduced) 1 else 0) ?: continue
            if (positional > parameters.size && parameters.lastOrNull()?.isParams != true) continue
            val names = parameters.map { it.name }
            if (!names.containsAll(used)) continue
            names.drop(positional).filterTo(result) { it.isNotEmpty() && it !in used }
        }
        return result.toList()
    }

    /** `[Obsolete(|`: the parameters of the attribute's constructors (`message:`) and its settable properties and fields (`DiagnosticId =`, true). */
    fun attributeArguments(argument: CSharpAttributeArgument, resolver: CSharpNameResolver): List<Pair<String, Boolean>> {
        val list = argument.parent as? CSharpAttributeArgumentList ?: return emptyList()
        val attribute = list.parent as? CSharpAttribute ?: return emptyList()
        val type = resolver.attributeType(attribute) ?: return emptyList()
        val used = list.arguments.filter { it != argument }.mapNotNullTo(HashSet()) { (it.nameColon?.nameElement ?: it.nameEquals?.nameElement)?.identifier?.text }
        val positional = list.arguments.take(list.arguments.indexOf(argument).coerceAtLeast(0)).count { it.nameColon == null && it.nameEquals == null }
        val parameters = LinkedHashSet<String>()
        val properties = LinkedHashSet<String>()
        when (type) {
            is SemanticType.Library -> {
                for ((name, members) in resolver.session.libraryMembers(resolver.assemblies, type.type)) {
                    val member = members.firstOrNull()?.member ?: continue
                    if (member.isStatic || member.isHidden || member.isProtected) continue
                    when (member.kind) {
                        IndexedMemberKind.PROPERTY -> if (member.hasSetter && !member.isInitOnly) properties += name
                        IndexedMemberKind.FIELD -> if (!member.isReadOnly) properties += name
                        else -> {}
                    }
                }
                for (constructor in type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden }) {
                    val names = resolver.session.parameters(constructor).map { it.name }
                    if (positional <= names.size) parameters += names.drop(positional)
                }
            }
            is SemanticType.Source -> {
                for ((name, member) in resolver.syntax.membersOf(type.info)) {
                    if (member.nestedType != null || NativeCSharpMembers.isStatic(member) || "public" !in member.modifiers) continue
                    val kind = NativeCSharpMembers.kind(member)
                    if (kind == NativeCSharpMembers.Kind.PROPERTY || kind == NativeCSharpMembers.Kind.FIELD && "readonly" !in member.modifiers) properties += name
                }
                for (declaration in type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }) {
                    for (constructor in declaration.members.filterIsInstance<CSharpConstructorDeclaration>()) {
                        val names = constructor.parameterList?.parameters.orEmpty().mapNotNull { it.identifier?.text }
                        if (positional <= names.size) parameters += names.drop(positional)
                    }
                }
            }
            else -> {}
        }
        return parameters.filter { it !in used }.map { it to false } + properties.filter { it !in used }.map { it to true }
    }

    // ---- a delegate that is no argument: `Changed += |`, `Func<int, bool> f = |`

    private fun delegateTarget(target: PsiElement, type: SemanticType?, at: CSharpSimpleName, text: CharSequence, resolver: CSharpNameResolver, result: MutableList<LookupElement>) {
        val delegate = type?.let(resolver.expressions::unwrapExpression) ?: return
        val lambda = NativeCSharpLambdas.forType(delegate, resolver) ?: return
        val statement = CSharpCalls.endsStatement(text, at.textRange.startOffset) && CSharpCalls.restOfLine(text, at.textRange.endOffset).isBlank()
        val head = lambda.head.trimEnd()
        val semicolon = if (statement) ";" else ""
        val inserted = "$head { }$semicolon"
        result += native(LookupElementBuilder.create(inserted).withPresentableText("$head {}").withIcon(AllIcons.Nodes.Lambda).withTypeText("lambda", true)
            .withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.startOffset + head.length + 3) }, LAMBDA)
        val method = NativeCSharpCreateMethod.plan(target, delegate, lambda, at, resolver) ?: return
        result += native(LookupElementBuilder.create(method.name).withLookupStrings(listOf(method.name, "Create method ${method.name}"))
            .withPresentableText("Create method ${method.name}(${method.parameterTypes.joinToString(", ")})").withIcon(AllIcons.Actions.IntentionBulb)
            .withInsertHandler(NativeCSharpCreateMethod.handler(method, semicolon)), LAMBDA - 1)
    }
}

/** The lambdas for a delegate the argument at an offset goes to, by the overloads of the call (the chosen one first). */
object NativeCSharpLambdas {
    /** [offset]: where the argument begins, right after `(` or `,` (whitespace aside). */
    fun at(file: CSharpFile, offset: Int, resolver: CSharpNameResolver = CSharpSemanticSession(file.project).resolver(file)): List<LambdaSuggestion> {
        val text = file.viewProvider.document?.charsSequence ?: file.text
        var i = offset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || (text[i] != '(' && text[i] != ',')) return emptyList()
        val token = file.findElementAt(i) ?: return emptyList()
        val list = token.parent as? CSharpArgumentList ?: return emptyList()
        val owner = list.parent ?: return emptyList()
        if (owner !is CSharpInvocationExpression && owner !is CSharpBaseObjectCreationExpression) return emptyList()
        val index = list.argumentsSeparators.count { it.textRange.startOffset <= i }
        val candidates = NativeCSharpParameterInfo.candidates(owner, resolver).ifEmpty { return emptyList() }
        return resolver.expressions.parameterTypesAt(owner as CSharpExpression, candidates, index)
            .mapNotNull { type -> type?.let { forType(it, resolver) } }.distinctBy { it.head }
    }

    /** `Func<Order, bool>` -> `order => `; a delegate of its own (`EventHandler`, a delegate of the solution) is named by the parameters of its `Invoke`. */
    fun forType(type: SemanticType, resolver: CSharpNameResolver): LambdaSuggestion? {
        val delegate = resolver.expressions.unwrapExpression(type) ?: return null
        val (parameters, _) = resolver.expressions.delegateSignature(delegate) ?: return null
        val shown = delegate.minimalDisplay ?: (delegate.name + (delegate as? SemanticType.Library)?.arguments?.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { it?.minimalDisplay ?: it?.name ?: "T" }.orEmpty())
        CSharpLambdaNames.forParameter(shown)?.takeIf { it.parameters.size == parameters.size }?.let { return it }
        val names = invokeNames(delegate)?.takeIf { it.size == parameters.size && it.all(String::isNotEmpty) }
            ?: CSharpLambdaNames.names(parameters.map { it?.minimalDisplay ?: it?.name ?: "T" })
        return LambdaSuggestion(names)
    }

    private fun invokeNames(delegate: SemanticType): List<String>? = when (delegate) {
        is SemanticType.Library -> delegate.type.members.firstOrNull { it.name == "Invoke" }?.parameters?.map { it.name }
        is SemanticType.Source -> delegate.info.parts.firstNotNullOfOrNull { it.element() as? CSharpDelegateDeclaration }?.parameterList?.parameters?.map { it.identifier?.text.orEmpty() }
        else -> null
    }
}

/** «Create method» at a delegate target: a method of the enclosing type with the delegate's signature, named as Rider names it (`OnChanged`). */
object NativeCSharpCreateMethod {
    class Plan(val name: String, val parameterTypes: List<String>, val text: String, val usings: Set<String>)

    fun plan(target: PsiElement, delegate: SemanticType, lambda: LambdaSuggestion, at: PsiElement, resolver: CSharpNameResolver): Plan? {
        val member = enclosingMember(at) ?: return null
        val name = nameFor(target) ?: return null
        val file = at.containingFile as? CSharpFile ?: return null
        val writer = CSharpCodeWriter(resolver, at, CSharpGenerateSite.nullableContext(file, at.textRange.startOffset))
        val (types, returns) = signature(delegate, writer, resolver) ?: return null
        val static = member.modifiers.any { it.text == "static" }
        val void = returns == "void"
        val body = if (void) "" else "    throw new ${writer.named("System.NotImplementedException")}();\n"
        val parameters = types.zip(lambda.parameters).joinToString(", ") { (type, parameter) -> "$type $parameter" }
        val text = "private ${if (static) "static " else ""}$returns $name($parameters)\n{\n$body}"
        return Plan(name, types, text, writer.usings.toSet())
    }

    /** The parameter types and the return type as the code here writes them: from the signature of the assembly where there is one (it keeps `object?`). */
    private fun signature(delegate: SemanticType, writer: CSharpCodeWriter, resolver: CSharpNameResolver): Pair<List<String>, String>? {
        if (delegate is SemanticType.Library) {
            val invoke = delegate.type.members.firstOrNull { it.name == "Invoke" } ?: return null
            val types = invoke.parameters.map { writer.ref(it.typeRef, delegate.arguments, emptyList()) ?: return null }
            return types to (writer.ref(invoke.typeRef, delegate.arguments, emptyList()) ?: return null)
        }
        val (parameters, returns) = resolver.expressions.delegateSignature(delegate) ?: return null
        return parameters.map { writer.type(it) ?: return null } to (returns?.let(writer::type) ?: "void")
    }

    /** `Changed` -> `OnChanged`, `button.Click` -> `ButtonOnClick`, `Func<int, bool> filter = ` -> `Filter`. */
    private fun nameFor(target: PsiElement): String? {
        fun pascal(name: String) = name.trimStart('_', '@').replaceFirstChar { it.uppercase() }
        return when (target) {
            is CSharpVariableDeclarator -> target.identifier?.text?.let(::pascal)
            is CSharpIdentifierName -> target.identifier?.text?.let { "On" + pascal(it) }
            is CSharpMemberAccessExpression -> {
                val right = target.nameElement?.identifier?.text ?: return null
                val left = (target.expression as? CSharpSimpleName)?.identifier?.text?.takeIf { target.expression !is CSharpThisExpression }
                (left?.let(::pascal) ?: "") + "On" + pascal(right)
            }
            else -> null
        }?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    /** A method, constructor, property or other member right in a type: where the new method goes after. */
    private fun enclosingMember(at: PsiElement): CSharpMemberDeclaration? {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            if (current is CSharpMemberDeclaration && current.parent is CSharpBaseTypeDeclaration) return current
            current = current.parent
        }
        return null
    }

    fun handler(plan: Plan, semicolon: String): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        val document = context.document
        document.replaceString(context.startOffset, context.tailOffset, plan.name + semicolon)
        val caret = context.startOffset + plan.name.length + semicolon.length
        context.editor.caretModel.moveToOffset(caret)
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
        val member = context.file.findElementAt(context.startOffset)?.let(::enclosingMember) ?: return@InsertHandler
        val end = member.textRange.endOffset
        val indent = CSharpExpressions.indentAt(document.charsSequence, member.textRange.startOffset)
        val method = plan.text.lines().joinToString("\n") { if (it.isEmpty()) it else indent + it }
        document.insertString(end, "\n\n$method")
        var text: CharSequence = document.charsSequence
        for (namespace in plan.usings) {
            if (CSharpUsings.isVisible(namespace, text)) continue
            val insertion = CSharpUsings.insertion(text, namespace) ?: continue
            document.insertString(insertion.offset, insertion.text)
            text = document.charsSequence
        }
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
    }
}

/** What follows a method chosen in the native list with its parentheses: the parameter info and the gray text of the arguments, as the server's items get them. */
object NativeCSharpCallPopups {
    /** Counted for the tests: the parameter info itself does not show in a headless editor. */
    @Volatile
    var requests: Int = 0
        private set

    fun afterCall(editor: Editor) {
        val project = editor.project ?: return
        requests++
        AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        NativeCSharpLambdaGhost.offer(editor)
    }
}
