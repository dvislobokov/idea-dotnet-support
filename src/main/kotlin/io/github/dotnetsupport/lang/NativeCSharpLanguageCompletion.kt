package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpTypeDisplay
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lsp.RoslynOptions
import javax.swing.Icon

/**
 * The places of the completion that language knows and the native list ([NativeCSharpCompletionContributor]) has no place for (0.1.94),
 * as Rider's `ExplicitInterfaceMember`, `Indexer`, `TupleName` and `PartialType` completions:
 *  - `void IFoo.|`, `int IFoo.|`, `IFoo.|` at the start of a member: the members of `IFoo` not implemented explicitly yet, written whole with
 *    a body (like the override completion); `void |` offers the implemented interfaces, which write `IFoo.` and open the list;
 *  - `x.|` of a value with an indexer (an array, a string, a list, a type of the solution with `this[...]`): `[]`, which turns `x.` into `x[|]`;
 *  - `t.|` of a tuple with named elements: the names; `var (|, b) = pair;` / `(var x, var y) = pair;` / `foreach (var (|, ) in pairs)`: the names
 *    of the elements of what is deconstructed — of the tuple, of `Deconstruct`, of the positional record;
 *  - `partial class |` (struct, record, interface): the partial types of the same namespace declared in other files.
 * A contributor of its own, in front of the native one, so that the lists of [NativeCSharpCompletion] stay as they are; where the place is
 * the explicit implementation's (the native list has nothing to say there but the nested types) it stops the others.
 */
class NativeCSharpLanguageCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val file = parameters.position.containingFile as? CSharpFile ?: return
        if (file.compilationUnit == null) return
        val prefix = result.prefixMatcher.prefix
        val text = parameters.editor.document.charsSequence
        val dot = text.getOrNull(parameters.offset - prefix.length - 1) == '.'
        // a list that opened by itself after a space with nothing typed: only the ones the native list opens too ([NativeCSharpCompletion.opensByItself])
        if (parameters.isAutoPopup && prefix.isEmpty() && !dot) return
        val found = NativeCSharpLanguageCompletion.items(parameters.position, file, result.prefixMatcher) ?: return
        found.items.forEach(result::addElement)
        if (found.stop && found.items.isNotEmpty()) result.stopHere()
    }
}

object NativeCSharpLanguageCompletion {
    class Found(val items: List<LookupElement>, val stop: Boolean = false)

    fun items(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): Found? {
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        explicitMembers(leaf, file, matcher)?.let { return Found(it, stop = true) }
        interfaceNames(leaf, file, matcher)?.let { return Found(it) }
        partialTypes(leaf, file, matcher)?.let { return Found(it) }
        // names of `var (a, b)` are name suggestions too: the same option as the names after a type
        if (RoslynOptions.isOn(NativeCSharpCompletion.NAME_SUGGESTIONS)) deconstructionNames(leaf, file, matcher)?.let { return Found(it) }
        memberAccess(leaf, file, matcher)?.let { return Found(it) }
        return null
    }

    // ---- explicit implementations

    /** `void IFoo.|`: the interface written before the dot, and where the member being typed begins. */
    private fun explicitPlace(leaf: PsiElement): Pair<CSharpType, Int>? {
        val parent = leaf.parent
        when {
            parent is CSharpMethodDeclaration && parent.identifier == leaf -> {
                val specifier = parent.explicitInterfaceSpecifier ?: return null
                return (specifier.nameElement as? CSharpType ?: return null) to (parent.returnType?.textRange?.startOffset ?: return null)
            }
            parent is CSharpPropertyDeclaration && parent.identifier == leaf -> {
                val specifier = parent.explicitInterfaceSpecifier ?: return null
                return (specifier.nameElement as? CSharpType ?: return null) to (parent.type?.textRange?.startOffset ?: return null)
            }
            parent is CSharpSimpleName && parent.identifier == leaf -> {
                val qualified = parent.parent as? CSharpQualifiedName ?: return null
                if (qualified.right != parent) return null
                val member = qualified.parent as? CSharpIncompleteMember ?: return null
                if (member.type != qualified) return null
                return (qualified.left as? CSharpType ?: return null) to qualified.textRange.startOffset
            }
        }
        return null
    }

    private fun explicitMembers(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val (written, from) = explicitPlace(leaf) ?: return null
        val type = PsiTreeUtil.getParentOfType(leaf, CSharpTypeDeclaration::class.java) ?: return null
        val site = CSharpGenerateSite.at(file, leaf.textRange.startOffset)?.takeIf { it.type == type } ?: return null
        val face = site.resolver.resolveType(written) ?: return null
        val inherited = NativeCSharpInheritedMembers(site)
        val known = inherited.interfaceNamed(CSharpTypeDisplay.display(face)) ?: return null
        return inherited.explicitItems(known).filter { matcher.prefixMatches(it.name) }.map { item ->
            val candidate = item.candidate
            var builder = LookupElementBuilder.create(candidate.header, item.name).withIcon(if (candidate.property) AllIcons.Nodes.Property else AllIcons.Nodes.Method)
                .withTailText(candidate.tail + " { ... }", true).bold()
            candidate.type?.let { builder = builder.withTypeText(it) }
            builder = builder.withInsertHandler(InsertHandler { context, _ -> NativeCSharpOverrides.insert(context, candidate, null, from) })
            builder.putUserData(NativeCSharpCompletion.NATIVE, true)
            PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.DECLARATION).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
        }
    }

    private val NOT_BEFORE_EXPLICIT = setOf("public", "private", "protected", "internal", "static", "virtual", "override", "abstract", "sealed", "readonly", "const", "new", "partial")

    /** `void |`, `int |`, `Order |` at the start of a member of a type that implements interfaces: `IFoo` writes `IFoo.` and opens the list of its members. */
    private fun interfaceNames(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val declarator = leaf.parent as? CSharpVariableDeclarator ?: return null
        if (declarator.identifier != leaf || declarator.initializer != null) return null
        val declaration = declarator.parent as? CSharpVariableDeclaration ?: return null
        val field = declaration.parent as? CSharpFieldDeclaration ?: return null
        val type = field.parent as? CSharpTypeDeclaration ?: return null
        if (type is CSharpInterfaceDeclaration || declaration.variables.size != 1) return null
        if (field.modifiers.any { it.text in NOT_BEFORE_EXPLICIT } || declaration.type?.text in setOf("partial", "async", "required", "file")) return null
        val site = CSharpGenerateSite.at(file, leaf.textRange.startOffset)?.takeIf { it.type == type } ?: return null
        val inherited = NativeCSharpInheritedMembers(site)
        val items = inherited.explicitInterfaces().mapNotNull { face ->
            val name = CSharpTypeDisplay.display(face, qualified = false) ?: return@mapNotNull null
            if (!matcher.prefixMatches(name)) return@mapNotNull null
            val builder = LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Interface).withTailText(" (explicit implementation)", true)
                .withInsertHandler(InsertHandler { context, _ ->
                    context.document.insertString(context.tailOffset, ".")
                    context.editor.caretModel.moveToOffset(context.tailOffset)
                    context.commitDocument()
                    AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
                })
            builder.putUserData(NativeCSharpCompletion.NATIVE, true)
            PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.TYPE - 5).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
        }
        return items.ifEmpty { null }
    }

    // ---- partial types

    /** `partial class |`: the names of the partial types of the namespace that have a part in another file. */
    private fun partialTypes(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val declaration = leaf.parent as? CSharpTypeDeclaration ?: return null
        if (declaration.identifier != leaf || declaration.modifiers.none { it.text == "partial" }) return null
        val around = generateSequence(declaration.parent) { it.parent }.takeWhile { it !is CSharpFile }.toList()
        // a type nested in another one: its partial siblings are members, not names of a namespace
        if (around.any { it is CSharpBaseTypeDeclaration }) return null
        val namespace = around.filterIsInstance<CSharpBaseNamespaceDeclaration>().asReversed().joinToString(".") { NativeCSharpResolver.compact(it.nameElement) }
        val kind = declaration.node.elementType
        val resolver = NativeCSharpResolver(file)
        val own = file.originalFile
        val result = ArrayList<LookupElement>()
        val seen = HashSet<String>()
        for ((name, _) in NativeCSharpTypeNames.candidates(file, matcher, resolver)) {
            val qualified = if (namespace.isEmpty()) name else "$namespace.$name"
            for (part in resolver.typeParts(name)) {
                if (part.qualifiedName != qualified || part.elementType != kind || "partial" !in part.modifiers) continue
                val element = part.element()
                val other = element?.containingFile?.originalFile
                if (other == null || other == own) continue
                val shown = name + (genericSuffix(element, part.arity))
                if (!seen.add(shown)) continue
                val builder = LookupElementBuilder.create(shown).withIcon(icon(part)).withTailText(" (${element.containingFile.name})", true)
                builder.putUserData(NativeCSharpCompletion.NATIVE, true)
                result += PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.TYPE).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
            }
        }
        return result.ifEmpty { null }
    }

    private fun genericSuffix(element: PsiElement, arity: Int): String {
        if (arity == 0) return ""
        return (element as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() } ?: (1..arity).joinToString(", ", "<", ">") { "T$it".takeIf { arity > 1 } ?: "T" }
    }

    private fun icon(part: TypePart): Icon = when (TypeKind.of(part.elementType, part.modifiers)) {
        TypeKind.INTERFACE -> AllIcons.Nodes.Interface
        TypeKind.RECORD, TypeKind.RECORD_STRUCT -> AllIcons.Nodes.Record
        else -> AllIcons.Nodes.Class
    }

    // ---- deconstruction

    private val RECORDS = setOf(TypeKind.RECORD, TypeKind.RECORD_STRUCT)

    /** A designation of `var (a, b) = pair`, `(var a, var b) = pair`, `foreach (var (a, b) in pairs)`: the names the element of what is deconstructed has. */
    private fun deconstructionNames(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val designation = leaf.parent as? CSharpSingleVariableDesignation ?: return null
        if (designation.identifier != leaf) return null
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val suggestion = deconstructedName(designation, resolver) ?: return null
        val taken = NativeCSharpLocals.visible(NativeCSharpResolver(file).scopes, leaf).mapTo(HashSet()) { it.name }
        val name = CSharpVariableNames.unique(suggestion, taken)
        if (!matcher.prefixMatches(name)) return null
        val builder = LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Variable)
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return listOf(PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.DECLARATION + 5).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) })
    }

    /** The name of the element at the place of [designation]: the name of the tuple element, of the parameter of `Deconstruct`, of the positional parameter of a record. */
    fun deconstructedName(designation: CSharpSingleVariableDesignation, resolver: CSharpNameResolver): String? {
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
            holder is CSharpAssignmentExpression && holder.left == at -> holder.right?.let(resolver::typeOf)
            holder is CSharpForEachVariableStatement && holder.variable == at -> holder.expression?.let(resolver::typeOf)?.let(resolver::elementType)
            else -> null
        }
        val ordered = path.asReversed()
        for ((position, index) in ordered.withIndex()) {
            val current = type ?: return null
            if (index < 0) return null
            if (position == ordered.lastIndex) return elementName(current, index, resolver)?.let(::localName)
            type = elementType(current, index, resolver)
        }
        return null
    }

    private fun elementType(type: SemanticType, index: Int, resolver: CSharpNameResolver): SemanticType? {
        if (type is SemanticType.Library && type.fullName.startsWith("System.ValueTuple`")) return type.arguments.getOrNull(index)
        val method = deconstruct(type, resolver) ?: return null
        return resolver.signature(method, false)?.getOrNull(index)?.type?.invoke()
    }

    private fun elementName(type: SemanticType, index: Int, resolver: CSharpNameResolver): String? {
        if (type is SemanticType.Library && type.fullName.startsWith("System.ValueTuple`")) return type.tupleNames?.getOrNull(index)?.takeIf { !TUPLE_ITEM.matches(it) }
        deconstruct(type, resolver)?.let { return resolver.signature(it, false)?.getOrNull(index)?.name }
        // a positional record gets its `Deconstruct` from the compiler
        if (type is SemanticType.Source && type.info.kind in RECORDS) {
            val parameters = type.info.parts.firstNotNullOfOrNull { (it.element() as? CSharpTypeDeclaration)?.parameterList }
            return parameters?.parameters?.getOrNull(index)?.identifier?.text
        }
        return null
    }

    private fun deconstruct(type: SemanticType, resolver: CSharpNameResolver): CSharpSymbol? =
        resolver.membersNamed(type, "Deconstruct", 0).filter { resolver.isMethod(it) && !resolver.isExtension(it) }.singleOrNull()

    private val TUPLE_ITEM = Regex("""Item\d+""")

    private fun localName(name: String): String = NativeCSharpGenerate.escape(name.replaceFirstChar { it.lowercaseChar() })

    // ---- after the dot

    private fun memberAccess(leaf: PsiElement, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val name = leaf.parent as? CSharpSimpleName ?: return null
        if (name.identifier != leaf) return null
        val holder = name.parent
        val access = holder is CSharpMemberAccessExpression && holder.nameElement == name || holder is CSharpMemberBindingExpression
        if (!access) return null
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val qualifier = CSharpMemberLookup(resolver).qualifierOf(name)
        val value = when (qualifier) {
            is CSharpNameResolver.Qualifier.Value -> qualifier.type
            is CSharpNameResolver.Qualifier.ValueOrType -> qualifier.value
            else -> return null
        }
        val result = ArrayList<LookupElement>()
        if (value is SemanticType.Library && value.fullName.startsWith("System.ValueTuple`")) {
            for ((index, element) in value.tupleNames.orEmpty().withIndex()) {
                if (element == null || TUPLE_ITEM.matches(element) || !matcher.prefixMatches(element)) continue
                val type = value.arguments.getOrNull(index)?.let { CSharpTypeDisplay.display(it, qualified = false) }
                var builder = LookupElementBuilder.create(element).withIcon(AllIcons.Nodes.Field)
                if (type != null) builder = builder.withTypeText(type)
                builder.putUserData(NativeCSharpCompletion.NATIVE, true)
                result += PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.VALUE_MEMBER + 5).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
            }
        }
        if (matcher.prefixMatches("[]")) indexerTail(value, resolver)?.let { tail ->
            val builder = LookupElementBuilder.create("[]").withIcon(AllIcons.Nodes.Property).withTailText(" $tail", true).withInsertHandler(INDEXER_HANDLER)
            builder.putUserData(NativeCSharpCompletion.NATIVE, true)
            result += PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.METHOD - 5).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
        }
        return result.ifEmpty { null }
    }

    /** `x.` → `x[|]`: the dot goes, the brackets come, the caret is between them. */
    private val INDEXER_HANDLER = InsertHandler<LookupElement> { context, _ ->
        val dot = context.startOffset - 1
        if (context.document.charsSequence.getOrNull(dot) != '.') return@InsertHandler
        context.document.replaceString(dot, context.tailOffset, "[]")
        context.editor.caretModel.moveToOffset(dot + 1)
        context.commitDocument()
    }

    /** `this[int index]` of the first indexer of [type]; null when it has none. */
    fun indexerTail(type: SemanticType, resolver: CSharpNameResolver, depth: Int = 0): String? {
        if (depth > 8) return null
        return when (type) {
            is SemanticType.ArrayOf -> "this[int index]"
            is SemanticType.Library -> {
                for ((_, found) in resolver.session.libraryMembers(resolver.assemblies, type.type)) for (inherited in found) {
                    val member = inherited.member
                    if (member.kind != IndexedMemberKind.INDEXER || member.isStatic || member.isHidden || member.isProtected) continue
                    val arguments = resolver.declaringArguments(type, inherited.from)
                    val parameters = resolver.session.parameters(member).joinToString(", ") { p ->
                        (resolver.fromRef(p.typeRef, arguments)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: "object") + " " + p.name
                    }
                    return "this[$parameters]"
                }
                null
            }
            is SemanticType.Source -> {
                for (part in type.info.parts) {
                    val indexer = (part.element() as? CSharpTypeDeclaration)?.members?.firstOrNull { it is CSharpIndexerDeclaration && it.modifiers.none { m -> m.text == "private" } } as? CSharpIndexerDeclaration
                    if (indexer != null) return "this" + CSharpStubsText.collapse(indexer.parameterList?.text ?: "[]")
                }
                resolver.baseTypes(type).firstNotNullOfOrNull { indexerTail(it, resolver, depth + 1) }
            }
            else -> null
        }
    }
}
