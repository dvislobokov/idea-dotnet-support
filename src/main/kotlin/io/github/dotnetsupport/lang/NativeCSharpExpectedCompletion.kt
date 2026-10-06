package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText
import io.github.dotnetsupport.lang.semantic.SemanticType
import javax.swing.Icon

/**
 * COMPLETION by the expected type (0.1.88), on the plugin's semantics ([CSharpNameResolver]): what Rider offers because it knows which type
 * the value at the caret converts to (`docs/rider-analysis`, dumps 3, 7–14, 26, 38, 39, 42, 46).
 *  - Object, collection and `with` initializers and property patterns: `new Order { Id = 1, |` and `o with { |` list only the members
 *    that can be assigned and are not assigned yet; `o is { |` the members of the type, nested patterns too (`{ Customer: { |`).
 *  - The members of an expected enum as qualified rows `OrderStatus.Paid` at the top: `status == |`, `case |`, an arm of `switch { |`,
 *    `x is |`, `{ Status: |`, an argument, an assignment, an initializer, `return`.
 *  - `await Highlights` for a member or local of a task whose result is what is wanted, in a function that is or can be made `async`
 *    (Roslyn's `AwaitCompletionProvider`): choosing it writes `await` and makes the function `async`.
 *  - `new |` with a target type: that type first, then the types derived from it / implementing it; `throw new |` only exceptions,
 *    `catch (|` exceptions first; a base list classes and interfaces, `event |` delegates, a constraint classes and interfaces.
 *  - Smart completion (Ctrl+Shift+Space): only what converts to the expected type.
 * Where the expected type is not known nothing is filtered and nothing is added: the list is the one of [NativeCSharpCompletion].
 */
object NativeCSharpExpectedCompletion {
    // above the common calls of `return` (300): as in Rider, the members of the expected enum open the list
    const val ENUM_MEMBER = 350.0
    const val EXPECTED_NEW = 340.0
    const val AWAIT = NativeCSharpCompletion.VALUE_MEMBER + NativeCSharpCompletion.EXPECTED_TYPE - 1
    const val NEW_ROW = NativeCSharpCompletion.VALUE_MEMBER + NativeCSharpCompletion.EXPECTED_TYPE - 2
    private const val MAX_LIBRARY_TYPES = 400

    /** What a type place takes ([Analysis.typeRole]). */
    enum class TypeRole(val keywords: Boolean) {
        ANY(true), NEW(true), EXCEPTION_ONLY(false), EXCEPTION_FIRST(true), BASE_CLASS_LIST(false), BASE_INTERFACE_LIST(false), EVENT(false), CONSTRAINT(false),
    }

    enum class Verdict { REJECT, NEUTRAL, FITS }

    /** The semantics of one completion: computed when first asked, once. */
    class Analysis(val place: NativeCSharpCompletionPlace, val file: CSharpFile) {
        val resolver: CSharpNameResolver by lazy { CSharpSemanticSession(file.project).resolver(file) }

        /** The type the value at the caret converts to; null when not known (or when it says nothing: `object`, `dynamic`, a type parameter). */
        val expected: SemanticType? by lazy { runCatching { expectedType() }.getOrNull()?.takeIf(::specific) }

        /** The caret is a constant of a pattern (`is |`, `case |`, an arm): a value is matched, not converted. */
        val pattern: Boolean by lazy { inPattern() }

        val typeRole: TypeRole by lazy { roleOf() }

        val at: PsiElement get() = place.name ?: place.leaf

        private fun specific(type: SemanticType): Boolean {
            if (type is SemanticType.Parameter) return false
            val name = resolver.definitionName(type)
            return name != null && name != "System.Object" && name != "System.Void"
        }

        private fun expectedType(): SemanticType? {
            val name = place.name ?: return null
            var value: PsiElement = name
            val creation = name.parent as? CSharpObjectCreationExpression
            if (creation != null && creation.type == name) value = creation
            while (value.parent is CSharpParenthesizedExpression) value = value.parent
            val parent = value.parent
            return when {
                parent is CSharpThrowStatement || parent is CSharpThrowExpression -> resolver.libraryType(EXCEPTION)
                parent is CSharpBinaryExpression && parent.right == value -> when (parent.operatorToken?.text) {
                    "==", "!=", "is" -> parent.left?.let(resolver::typeOf)
                    else -> (value as? CSharpExpression)?.let(resolver.expressions::target)
                }
                parent is CSharpCaseSwitchLabel && parent.value == value -> PsiTreeUtil.getParentOfType(parent, CSharpSwitchStatement::class.java)?.expression?.let(resolver::typeOf)
                parent is CSharpConstantPattern -> inputOf(parent)
                value is CSharpExpression -> resolver.expressions.target(value)
                else -> null
            }
        }

        private fun inPattern(): Boolean {
            val name = place.name ?: return false
            val parent = name.parent
            return parent is CSharpConstantPattern || parent is CSharpCaseSwitchLabel || parent is CSharpBinaryExpression && parent.operatorToken?.text == "is" && parent.right == name
        }

        /** The type a pattern tests: of the `is` / `switch`, or of the member a subpattern names (`{ Customer: { Name: | } }`). */
        fun inputOf(pattern: CSharpPattern): SemanticType? {
            var at: PsiElement = pattern
            while (at.parent is CSharpBinaryPattern || at.parent is CSharpParenthesizedPattern || at.parent is CSharpUnaryPattern) at = at.parent
            val subpattern = at.parent as? CSharpSubpattern ?: return resolver.expressions.patternInputType(pattern)
            val clause = subpattern.parent as? CSharpPropertyPatternClause ?: return null
            val owner = clause.parent as? CSharpRecursivePattern ?: return null
            val input = recursiveInput(owner) ?: return null
            return memberChainType(input, subpattern.expressionColon?.expression ?: return null)
        }

        /** What a `{ ... }` pattern matches: its written type, or what its position tests. */
        fun recursiveInput(pattern: CSharpRecursivePattern): SemanticType? = pattern.type?.let(resolver::resolveType) ?: inputOf(pattern)

        /** `Status` / `Customer.Name` of a subpattern: the type of that member of [type]. */
        private fun memberChainType(type: SemanticType, expression: CSharpExpression): SemanticType? = when (expression) {
            is CSharpIdentifierName -> expression.identifier?.text?.let { member(type, it) }
            is CSharpMemberAccessExpression -> expression.expression?.let { memberChainType(type, it) }?.let { left ->
                (expression.nameElement as? CSharpIdentifierName)?.identifier?.text?.let { member(left, it) }
            }
            else -> null
        }

        private fun member(type: SemanticType, name: String): SemanticType? =
            resolver.membersNamed(type, name, 0).firstOrNull { !resolver.isMethod(it) }?.let(resolver::valueType)

        /** Whether a value of [type] goes where [expected] is wanted. */
        fun fits(type: SemanticType?, expected: SemanticType): Boolean {
            type ?: return false
            return resolver.conversion(type, expected).let { it == CSharpNameResolver.Conversion.IDENTITY || it == CSharpNameResolver.Conversion.IMPLICIT }
        }

        private fun roleOf(): TypeRole {
            if (place.kind != NativeCompletionKind.TYPE) return TypeRole.ANY
            val name = place.name ?: return TypeRole.ANY
            var top: PsiElement = name
            while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
            if (top != name) return TypeRole.ANY
            return when (val owner = top.parent) {
                is CSharpObjectCreationExpression -> {
                    var value: PsiElement = owner
                    while (value.parent is CSharpParenthesizedExpression) value = value.parent
                    if (value.parent is CSharpThrowStatement || value.parent is CSharpThrowExpression) TypeRole.EXCEPTION_ONLY else TypeRole.NEW
                }
                is CSharpCatchDeclaration -> TypeRole.EXCEPTION_FIRST
                is CSharpSimpleBaseType, is CSharpPrimaryConstructorBaseType -> when (PsiTreeUtil.getParentOfType(owner, CSharpBaseTypeDeclaration::class.java)) {
                    is CSharpEnumDeclaration -> TypeRole.ANY
                    is CSharpClassDeclaration, is CSharpRecordDeclaration -> if (isRecordStruct(owner)) TypeRole.BASE_INTERFACE_LIST else TypeRole.BASE_CLASS_LIST
                    else -> TypeRole.BASE_INTERFACE_LIST
                }
                is CSharpTypeConstraint -> TypeRole.CONSTRAINT
                is CSharpVariableDeclaration -> if (owner.parent is CSharpEventFieldDeclaration) TypeRole.EVENT else TypeRole.ANY
                is CSharpEventDeclaration -> TypeRole.EVENT
                else -> TypeRole.ANY
            }
        }

        /** The type whose base list the caret is in: it does not derive from itself. */
        private val ownType: TypeInfo? by lazy { PsiTreeUtil.getParentOfType(at, CSharpBaseTypeDeclaration::class.java)?.let { resolver.syntax.declaredType(it) } }

        private fun isRecordStruct(element: PsiElement): Boolean =
            PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java)?.node?.elementType == SyntaxKind.RecordStructDeclaration

        /** What [TypeRole] wants of a type of the solution. */
        fun verdict(info: TypeInfo): Verdict {
            if (typeRole == TypeRole.ANY) return Verdict.NEUTRAL
            val kind = info.kind ?: return Verdict.NEUTRAL
            val modifiers = info.parts.flatMapTo(HashSet()) { it.modifiers }
            val own = ownType
            return when (typeRole) {
                TypeRole.ANY -> Verdict.NEUTRAL
                TypeRole.BASE_CLASS_LIST -> when {
                    own?.key == info.key -> Verdict.REJECT
                    kind == TypeKind.INTERFACE -> Verdict.NEUTRAL
                    (kind == TypeKind.CLASS || kind == TypeKind.RECORD) && "sealed" !in modifiers -> Verdict.NEUTRAL
                    else -> Verdict.REJECT
                }
                TypeRole.BASE_INTERFACE_LIST -> if (kind == TypeKind.INTERFACE && own?.key != info.key) Verdict.NEUTRAL else Verdict.REJECT
                TypeRole.CONSTRAINT -> if (kind == TypeKind.INTERFACE || (kind == TypeKind.CLASS || kind == TypeKind.RECORD) && "sealed" !in modifiers) Verdict.NEUTRAL else Verdict.REJECT
                TypeRole.EVENT -> if (kind == TypeKind.DELEGATE) Verdict.NEUTRAL else Verdict.REJECT
                TypeRole.NEW, TypeRole.EXCEPTION_ONLY, TypeRole.EXCEPTION_FIRST -> {
                    val wanted = expectedOfType() ?: return Verdict.NEUTRAL
                    val fitting = info.arity == 0 && fits(resolver.selfType(info), wanted) && (typeRole == TypeRole.EXCEPTION_FIRST || constructible(info))
                    when {
                        fitting -> Verdict.FITS
                        typeRole == TypeRole.EXCEPTION_ONLY -> Verdict.REJECT
                        else -> Verdict.NEUTRAL
                    }
                }
            }
        }

        /** The type a type place wants: of the value created, `Exception` after `throw new` and in `catch (`. */
        fun expectedOfType(): SemanticType? = when (typeRole) {
            TypeRole.EXCEPTION_ONLY, TypeRole.EXCEPTION_FIRST -> resolver.libraryType(EXCEPTION)
            TypeRole.NEW -> expected
            else -> null
        }

        fun constructible(info: TypeInfo): Boolean {
            val kind = info.kind ?: return false
            if (kind != TypeKind.CLASS && kind != TypeKind.RECORD && kind != TypeKind.STRUCT && kind != TypeKind.RECORD_STRUCT) return false
            return info.parts.none { "abstract" in it.modifiers || "static" in it.modifiers }
        }

        fun constructible(type: IndexedType): Boolean =
            type.kind == IndexedTypeKind.STRUCT || type.kind == IndexedTypeKind.CLASS && !type.isAbstract && !type.isStatic

        fun isEnum(type: SemanticType): Boolean = when (type) {
            is SemanticType.Library -> type.type.kind == IndexedTypeKind.ENUM
            is SemanticType.Source -> type.info.kind == TypeKind.ENUM
            else -> false
        }

        /** The expected enum (`OrderStatus?` too). */
        val expectedEnum: SemanticType? by lazy { expected?.let(resolver::unwrapNullable)?.takeIf(::isEnum) }
    }

    // ---- the auto-popup

    /** `status == `, `status != `, `case `, `Console.BackgroundColor = `: the list opens by itself when an enum is expected there (as in Rider). */
    fun opensAfterSpace(parameters: CompletionParameters): Boolean = opensAfterSpace(parameters.position)

    /** [opensAfterSpace] at the identifier the platform completes ([position], in the copy of the file). */
    fun opensAfterSpace(position: PsiElement): Boolean {
        val place = NativeCSharpCompletionPlace.of(position) ?: return false
        if (place.kind != NativeCompletionKind.EXPRESSION || place.prev?.text !in POPUP_AFTER) return false
        val file = position.containingFile as? CSharpFile ?: return false
        return runCatching { Analysis(place, file).expectedEnum != null }.getOrDefault(false)
    }

    /** A space typed after [position] (the token before the caret) may open the list: the place decides in [opensAfterSpace]. */
    fun invokesAutoPopup(position: PsiElement, typeChar: Char): Boolean =
        typeChar == ' ' && position.text in POPUP_AFTER && position.containingFile is CSharpFile && CSharpFeatures.native(CSharpFeature.COMPLETION, position.project)

    /** Tokens a space after which opens the list where an enum is expected ([opensAfterSpace]). */
    val POPUP_AFTER = setOf("==", "!=", "case", "=", "return")

    // ---- initializers and property patterns

    /**
     * `new Order { |`, `new Order { Id = 1, |`, `o with { |`, `o is { |`, `{ Id: 1, |`: the members to name there, null where the caret is
     * no member name of an initializer or a property pattern (or the type is not known: then the list is the usual one).
     */
    fun initializerMembers(analysis: Analysis): List<LookupElement>? {
        val name = analysis.place.name ?: return null
        val parent = name.parent
        if (parent !is CSharpInitializerExpression && parent !is CSharpConstantPattern) return null
        val r = analysis.resolver
        return when {
            parent is CSharpInitializerExpression && parent.expressions.contains(name) -> {
                val kind = parent.node.elementType
                val owner = parent.parent
                val created: SemanticType = when {
                    kind == SyntaxKind.WithInitializerExpression -> (owner as? CSharpWithExpression)?.expression?.let(r::typeOf)
                    owner is CSharpBaseObjectCreationExpression -> r.typeOf(owner)
                    // `Lines = { |` of a nested initializer: the member's type
                    owner is CSharpAssignmentExpression && owner.right == parent && NativeCSharpScopes.isObjectInitializer(owner.parent) -> owner.left?.let(r::typeOf)
                    else -> null
                } ?: return null
                when (kind) {
                    SyntaxKind.ObjectInitializerExpression, SyntaxKind.WithInitializerExpression -> {}
                    // `new Order { X|`: read as a collection initializer until `=` is typed; a collection's elements are values
                    SyntaxKind.CollectionInitializerExpression -> if (parent.expressions.size > 1 || isCollection(created, r)) return null
                    else -> return null
                }
                val assigned = parent.expressions.mapNotNullTo(HashSet()) { ((it as? CSharpAssignmentExpression)?.left as? CSharpIdentifierName)?.identifier?.text }
                // "Fill required members" / "Fill all members" of `new T { |` (0.1.98)
                val fill = if (kind == SyntaxKind.WithInitializerExpression) emptyList()
                else runCatching { NativeCSharpObjectInitializers.fillItems(parent, r, name, NativeCSharpCompletion.VALUE_MEMBER + 2) }.getOrDefault(emptyList())
                fill + members(analysis, created, assigned, settable = true)
            }
            parent is CSharpConstantPattern && parent.parent is CSharpSubpattern -> {
                val subpattern = parent.parent as CSharpSubpattern
                if (subpattern.expressionColon != null) return null
                val clause = subpattern.parent as? CSharpPropertyPatternClause ?: return null
                val owner = clause.parent as? CSharpRecursivePattern ?: return null
                val input = analysis.recursiveInput(owner) ?: return null
                val named = clause.subpatterns.mapNotNullTo(HashSet()) { (it.expressionColon as? CSharpNameColon)?.nameElement?.identifier?.text }
                members(analysis, input, named, settable = false)
            }
            else -> null
        }
    }

    private fun isCollection(type: SemanticType, r: CSharpNameResolver): Boolean =
        type is SemanticType.ArrayOf || r.expressions.instanceOf(type, "System.Collections.IEnumerable") != null ||
            r.expressions.instanceOf(type, "System.Collections.Generic.IEnumerable`1") != null

    private fun members(analysis: Analysis, type: SemanticType, taken: Set<String>, settable: Boolean): List<LookupElement> {
        val r = analysis.resolver
        val name = analysis.place.name ?: return emptyList()
        val text = CSharpSymbolText(r)
        val result = ArrayList<LookupElement>()
        for (entry in CSharpMemberLookup(r).entries(CSharpNameResolver.Qualifier.Value(type), name)) {
            ProgressManager.checkCanceled()
            if (entry.name in taken) continue
            val symbol = entry.first
            val (icon, property) = when (symbol) {
                is CSharpSymbol.SourceMember -> when (NativeCSharpMembers.kind(symbol.member)) {
                    NativeCSharpMembers.Kind.PROPERTY -> AllIcons.Nodes.Property to true
                    NativeCSharpMembers.Kind.FIELD -> AllIcons.Nodes.Field to false
                    else -> continue
                }
                is CSharpSymbol.LibraryMember -> when (symbol.member.kind) {
                    IndexedMemberKind.PROPERTY -> AllIcons.Nodes.Property to true
                    IndexedMemberKind.FIELD -> AllIcons.Nodes.Field to false
                    else -> continue
                }
                else -> continue
            }
            if (settable && !assignable(symbol, r, name)) continue
            var builder = LookupElementBuilder.create(entry.name).withIcon(icon).bold()
                .withTypeText(runCatching { text.typeOf(symbol) }.getOrNull())
            // `Sku = ` in an initializer, as Rider writes it
            if (settable) builder = builder.withInsertHandler(ASSIGNMENT)
            result += prioritized(builder, NativeCSharpCompletion.VALUE_MEMBER + if (property) 1 else 0)
        }
        return result
    }

    /** ` = ` after a member chosen in an object or `with` initializer, unless an `=` follows already. */
    private val ASSIGNMENT = InsertHandler<LookupElement> { context, _ ->
        val text = context.document.charsSequence
        var next = context.tailOffset
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        if (text.getOrNull(next) == '=') return@InsertHandler
        if (context.completionChar == '=') return@InsertHandler
        val at = context.tailOffset
        context.document.insertString(at, " = ")
        context.editor.caretModel.moveToOffset(at + 3)
        context.commitDocument()
    }

    /** A member an initializer may name: a settable or `init` property, a field that is not `readonly`, or a collection to fill (`Lines = { … }`). */
    private fun assignable(symbol: CSharpSymbol, r: CSharpNameResolver, site: PsiElement): Boolean {
        if (writable(symbol, site)) return true
        val type = r.valueType(symbol) ?: return false
        return type !is SemanticType.ArrayOf && r.definitionName(type) != "System.String" && isCollection(type, r)
    }

    /** A settable or `init` property (its setter seen from [site]), a field that is not `readonly`: `Name = value` compiles. */
    internal fun writable(symbol: CSharpSymbol, site: PsiElement): Boolean =
        when (symbol) {
            is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
                // `private set` / `protected set`: not from outside the type
                is CSharpPropertyDeclaration -> element.accessorList?.accessors.orEmpty().any { accessor ->
                    (accessor.keyword?.text == "set" || accessor.keyword?.text == "init") &&
                        (accessor.modifiers.none { it.text == "private" || it.text == "protected" } || PsiTreeUtil.isAncestor(element.parent, site, true))
                }
                is CSharpVariableDeclarator -> (element.parent?.parent as? CSharpBaseFieldDeclaration)?.modifiers.orEmpty().none { it.text == "readonly" || it.text == "const" }
                is CSharpBaseFieldDeclaration -> element.modifiers.none { it.text == "readonly" || it.text == "const" }
                // a positional parameter of a record: an `init` property
                else -> element is CSharpParameter || element.parent is CSharpParameter
            }
            is CSharpSymbol.LibraryMember -> when (symbol.member.kind) {
                IndexedMemberKind.PROPERTY -> symbol.member.hasSetter || symbol.member.isInitOnly
                IndexedMemberKind.FIELD -> !symbol.member.isReadOnly
                else -> false
            }
            else -> false
        }

    // ---- values of an expected type (basic completion)

    /** The rows a value place adds when its type is expected: the enum's members, `await` of a task of it, `new T()`. */
    fun expressionItems(analysis: Analysis): List<LookupElement> {
        if (analysis.place.kind != NativeCompletionKind.EXPRESSION) return emptyList()
        val expected = analysis.expected ?: return emptyList()
        val result = ArrayList<LookupElement>()
        enumRows(analysis)?.let(result::addAll)
        if (analysis.pattern) return result
        result += awaitRows(analysis, expected)
        newRow(analysis, expected)?.let(result::add)
        return result
    }

    /** `OrderStatus.Paid` for each member of the expected enum, its value at the right (as Rider shows `: 1`). */
    fun enumRows(analysis: Analysis): List<LookupElement>? {
        val type = analysis.expectedEnum ?: return null
        val qualifier = type.minimalDisplay ?: type.name
        return enumMembers(type).map { (member, value) ->
            // looked up by the member's name alone: a lookup string starting with the type's name would lift the type's own row over the
            // members (the platform's "lift shorter items"); the row shows and inserts the qualified name
            val qualified = "$qualifier.$member"
            val builder = LookupElementBuilder.create(member).withPresentableText(qualified).withIcon(AllIcons.Nodes.Constant).withTypeText(value)
                .withInsertHandler { context, _ ->
                    context.document.replaceString(context.startOffset, context.tailOffset, qualified)
                    context.editor.caretModel.moveToOffset(context.startOffset + qualified.length)
                    context.tailOffset = context.startOffset + qualified.length
                    closeStatement(context)
                    context.commitDocument()
                }
            prioritized(builder, ENUM_MEMBER)
        }
    }

    /**
     * A value chosen at the end of a line closes what it ends, as typing on would: `Console.BackgroundColor = ConsoleColor.Black|` gets `;`,
     * `Paint(ConsoleColor.Black|` gets `);`, `if (status == Status.New|` gets `)`. Nothing when something follows on the line, inside an
     * initializer (`Status = Status.New,`) or where the line is no whole statement (`.Where(x => x.Status == Status.New` of a chain).
     */
    fun closeStatement(context: com.intellij.codeInsight.completion.InsertionContext) {
        val document = context.document
        val text = document.charsSequence
        val end = context.tailOffset
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (text.subSequence(end, lineEnd).isNotBlank()) return
        if (CSharpCalls.inInitializer(text, end)) return
        val lineStart = text.lastIndexOf('\n', (end - 1).coerceAtLeast(0)) + 1
        val line = text.subSequence(lineStart, end).toString()
        val tokens = CSharpExpressions.tokenize(line)
        val first = tokens.firstOrNull() ?: return
        val open = tokens.count { it.type == CSharpTokenTypes.LPAREN } - tokens.count { it.type == CSharpTokenTypes.RPAREN }
        if (open < 0) return
        val closed = ")".repeat(open)
        val tail = when {
            CSharpCompleteStatement.needsSemicolon(line + closed) -> "$closed;"
            // `if (status == Status.New`: the header closed, its body is the next thing typed
            open > 0 && first.type == CSharpTokenTypes.KEYWORD && first.text in CLOSED_HEADERS -> closed
            else -> return
        }
        document.insertString(end, tail)
        context.editor.caretModel.moveToOffset(end + tail.length)
        context.tailOffset = end + tail.length
    }

    private val CLOSED_HEADERS = setOf("if", "while", "switch", "foreach", "for", "using", "lock")

    /** The members of an enum with their values as written (or counted on from the last one). */
    fun enumMembers(type: SemanticType): List<Pair<String, String?>> = when (type) {
        is SemanticType.Library -> type.type.members.filter { it.kind == IndexedMemberKind.ENUM_MEMBER }.map { it.name to it.constantValue }
        is SemanticType.Source -> {
            val result = ArrayList<Pair<String, String?>>()
            for (declaration in type.info.parts.mapNotNull { it.element() as? CSharpEnumDeclaration }) {
                var next: Long? = 0
                for (member in declaration.members) {
                    val name = member.identifier?.text ?: continue
                    val written = member.equalsValue?.value?.text?.trim()
                    val value = if (written != null) {
                        next = written.toLongOrNull()?.plus(1)
                        written
                    } else next?.also { next = it + 1 }?.toString()
                    result += name to value
                }
            }
            result
        }
        else -> emptyList()
    }

    /** `await Highlights`: the members and locals of a task whose result goes where the caret is, when `await` may stand there. */
    private fun awaitRows(analysis: Analysis, expected: SemanticType): List<LookupElement> {
        val at = analysis.at
        val state = NativeCSharpCommonCalls.awaitState(at)
        if (state == NativeCSharpCommonCalls.AwaitState.NO) return emptyList()
        val r = analysis.resolver
        val result = ArrayList<LookupElement>()
        val seen = HashSet<String>()
        fun row(name: String, type: SemanticType, method: Boolean, takesArguments: Boolean) {
            val awaited = r.expressions.awaited(type) ?: return
            if (!analysis.fits(awaited, expected) || !seen.add(name)) return
            val handler = InsertHandler<LookupElement> { context, item ->
                if (method) NativeCSharpCalls.callHandler { false to takesArguments }.handleInsert(context, item)
                if (state == NativeCSharpCommonCalls.AwaitState.FIXABLE) NativeCSharpCommonCalls.makeAsyncAt(context.file, context.startOffset, context.editor)
            }
            val builder = LookupElementBuilder.create("await $name").withLookupStrings(setOf("await $name", name))
                .withIcon(if (method) AllIcons.Nodes.Method else AllIcons.Nodes.Variable).withTypeText(type.minimalDisplay).withInsertHandler(handler)
            result += prioritized(builder, AWAIT)
        }
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, analysis.place.leaf)) {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER) continue
            r.valueType(CSharpSymbol.Local(symbol))?.let { row(symbol.name, it, false, false) }
        }
        val static = NativeCSharpLocals.inStaticContext(at)
        for (info in r.syntax.enclosingTypes(at)) for ((key, member) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || member.nestedType != null) continue
            if (static && !NativeCSharpMembers.isStatic(member)) continue
            val kind = NativeCSharpMembers.kind(member)
            if (kind != NativeCSharpMembers.Kind.METHOD && kind != NativeCSharpMembers.Kind.PROPERTY && kind != NativeCSharpMembers.Kind.FIELD) continue
            val symbols = r.membersNamed(r.selfType(info), key, 0)
            val first = symbols.firstOrNull() ?: continue
            val type = r.valueType(first) ?: continue
            val method = kind == NativeCSharpMembers.Kind.METHOD
            row(key, type, method, method && symbols.any { r.signature(it, false)?.isNotEmpty() != false })
        }
        return result
    }

    /** `new CancellationToken()` where a value of a type that can be created is wanted (Rider, dump 40). */
    private fun newRow(analysis: Analysis, expected: SemanticType): LookupElement? {
        if (analysis.expectedEnum != null || analysis.resolver.isNullable(expected)) return null
        val shown = expected.minimalDisplay ?: return null
        val creatable = when (expected) {
            is SemanticType.Library -> analysis.constructible(expected.type) && expected.type.fullName !in NOT_CREATED
            is SemanticType.Source -> analysis.constructible(expected.info)
            else -> false
        }
        if (!creatable) return null
        val initialized = NativeCSharpObjectInitializers.presentation(shown, expected, analysis.resolver)
        val builder = LookupElementBuilder.create("new $shown").withLookupStrings(setOf("new $shown", shown.substringBefore('<')))
            .withPresentableText("new " + (initialized ?: "$shown()")).withIcon(AllIcons.Nodes.Class).withInsertHandler(NativeCSharpCalls.typeHandler(generic = false, constructed = true))
        return prioritized(builder, NEW_ROW)
    }

    // ---- type places

    /** The rows a type place adds: the expected type of `new` first, the library types that fit `new` / `throw new` / `catch (` / `event`. */
    fun typeItems(analysis: Analysis): List<LookupElement> {
        val role = analysis.typeRole
        if (role == TypeRole.ANY || role == TypeRole.BASE_CLASS_LIST || role == TypeRole.BASE_INTERFACE_LIST || role == TypeRole.CONSTRAINT) return emptyList()
        val r = analysis.resolver
        val result = ArrayList<LookupElement>()
        if (role == TypeRole.EVENT) {
            for (type in importedLibraryTypes(analysis)) if (type.kind == IndexedTypeKind.DELEGATE) result += libraryTypeRow(type, NativeCSharpCompletion.TYPE, constructed = false)
            return result
        }
        val wanted = analysis.expectedOfType() ?: return emptyList()
        // `Order o = new |`: the type itself, the one obvious choice (Rider: `Order()` first)
        if (role == TypeRole.NEW) expectedTypeRow(analysis, wanted)?.let { row ->
            result += row
            expectedInitializerRow(analysis, wanted, row)?.let(result::add)
        }
        // nothing derives from a struct or a sealed class
        val final = when (wanted) {
            is SemanticType.Library -> wanted.type.kind != IndexedTypeKind.CLASS && wanted.type.kind != IndexedTypeKind.INTERFACE || wanted.type.isSealed
            is SemanticType.Source -> wanted.info.kind != TypeKind.CLASS && wanted.info.kind != TypeKind.RECORD && wanted.info.kind != TypeKind.INTERFACE ||
                wanted.info.parts.any { "sealed" in it.modifiers }
            else -> true
        }
        if (final) return result
        val constructed = role != TypeRole.EXCEPTION_FIRST
        for (type in importedLibraryTypes(analysis)) {
            ProgressManager.checkCanceled()
            if (type.ownArity > 0 || type.kind == IndexedTypeKind.INTERFACE || type.kind == IndexedTypeKind.ENUM || type.kind == IndexedTypeKind.DELEGATE) continue
            if (constructed && !analysis.constructible(type)) continue
            if (!analysis.fits(SemanticType.Library(type, emptyList()), wanted)) continue
            val row = libraryTypeRow(type, NativeCSharpCompletion.TYPE + NativeCSharpCompletion.EXPECTED_TYPE, constructed)
            if (role == TypeRole.EXCEPTION_FIRST) markException(row)
            result += row
            if (result.size > MAX_LIBRARY_TYPES) break
        }
        return result
    }

    // ---- `catch (`: the exceptions above everything

    /** On an item of `catch (`: a type that derives from `Exception` ([ExceptionsFirst] puts these first). */
    private val EXCEPTION_ROW: Key<Boolean> = Key.create("dotnet.csharp.exceptionRow")

    fun markException(element: LookupElement) = element.putUserData(EXCEPTION_ROW, true)

    private fun isException(element: LookupElement): Boolean {
        var current: LookupElement? = element
        while (current != null) {
            if (current.getUserData(EXCEPTION_ROW) == true) return true
            current = (current as? LookupElementDecorator<*>)?.delegate
        }
        return false
    }

    /**
     * The exceptions first in `catch (`, before the platform lifts a shorter name over a longer one it begins (`Ex`, an enum, came right
     * after `Exception`; `Index` after `IndexOutOfRangeException`): only what derives from `Exception` is in the first group.
     */
    fun exceptionsFirst(parameters: CompletionParameters, result: CompletionResultSet): CompletionResultSet =
        result.withRelevanceSorter(CompletionSorter.defaultSorter(parameters, result.prefixMatcher).weighBefore("liftShorter", ExceptionsFirst))

    private object ExceptionsFirst : LookupElementWeigher("dotnetExceptionsFirst") {
        override fun weigh(element: LookupElement): Comparable<*> = if (isException(element)) 0 else 1
    }

    /** `Order` of `Order o = new |` (`List<int>` of a generic one): first in the list, `()` after it. */
    private fun expectedTypeRow(analysis: Analysis, wanted: SemanticType): LookupElement? {
        val shown = wanted.minimalDisplay ?: return null
        val creatable = when (wanted) {
            is SemanticType.Library -> wanted.type.kind == IndexedTypeKind.STRUCT || wanted.type.kind == IndexedTypeKind.CLASS && !wanted.type.isAbstract && !wanted.type.isStatic
            is SemanticType.Source -> analysis.constructible(wanted.info)
            else -> false
        }
        if (!creatable || analysis.isEnum(wanted)) return null
        val icon = if (wanted is SemanticType.Source && (wanted.info.kind == TypeKind.RECORD || wanted.info.kind == TypeKind.RECORD_STRUCT)) AllIcons.Nodes.Record else AllIcons.Nodes.Class
        val initialized = NativeCSharpObjectInitializers.presentation(shown, wanted, analysis.resolver)
        val builder = LookupElementBuilder.create(shown).withLookupStrings(setOf(shown, shown.substringBefore('<'))).withPresentableText(initialized ?: "$shown()")
            .withIcon(icon).withInsertHandler(NativeCSharpCalls.typeHandler(generic = false, constructed = true))
        return prioritized(builder, EXPECTED_NEW)
    }

    /** `Member { … }` under the expected type's row (0.1.102): when it has no required members (its row writes those) and members to set. */
    private fun expectedInitializerRow(analysis: Analysis, wanted: SemanticType, row: LookupElement): LookupElement? {
        if (wanted !is SemanticType.Source && wanted !is SemanticType.Library) return null
        if (wanted is SemanticType.Library && wanted.arguments.isNotEmpty() || wanted is SemanticType.Source && wanted.info.arity > 0) return null
        val r = analysis.resolver
        if (NativeCSharpObjectInitializers.presentation(row.lookupString, wanted, r) != null || !NativeCSharpTypingGhost.parameterless(wanted)) return null
        if (r.expressions.instanceOf(wanted, "System.Collections.IEnumerable") != null) return null
        if (NativeCSharpObjectInitializers.settable(wanted, r, analysis.at, emptySet()).isEmpty()) return null
        val icon = if (wanted is SemanticType.Source && (wanted.info.kind == TypeKind.RECORD || wanted.info.kind == TypeKind.RECORD_STRUCT)) AllIcons.Nodes.Record else AllIcons.Nodes.Class
        return NativeCSharpObjectInitializers.initializerRow(row.lookupString, icon, null, EXPECTED_NEW - 0.01)
    }

    private fun libraryTypeRow(type: IndexedType, priority: Double, constructed: Boolean): LookupElement {
        val name = type.simpleName
        val generic = type.ownArity > 0
        val presentable = (if (generic) "$name<${"".padEnd(type.ownArity - 1, ',')}>" else name) + if (constructed) "()" else ""
        val icon: Icon = when (type.kind) {
            IndexedTypeKind.DELEGATE -> AllIcons.Nodes.Lambda
            IndexedTypeKind.INTERFACE -> AllIcons.Nodes.Interface
            else -> AllIcons.Nodes.Class
        }
        var builder = LookupElementBuilder.create(name).withPresentableText(presentable).withIcon(icon).withTailText(" (${type.namespace})", true)
        if (generic || constructed) builder = builder.withInsertHandler(NativeCSharpCalls.typeHandler(generic, constructed))
        return prioritized(builder, priority)
    }

    /** The top-level types of the assemblies in the namespaces the place imports (and its own). */
    private fun importedLibraryTypes(analysis: Analysis): Sequence<IndexedType> {
        val r = analysis.resolver
        val namespaces = runCatching { r.visibleNamespaces(analysis.at) }.getOrDefault(emptySet())
        return namespaces.asSequence().flatMap { r.assemblies.typesIn(it).asSequence() }
            .filter { it.declaringType == null && !it.isHidden && !it.simpleName.startsWith("<") && !it.obsolete }
    }

    // ---- smart completion

    /**
     * Ctrl+Shift+Space: only what converts to the expected type — locals, parameters, members of the enclosing types (methods by what they
     * return), static members of the expected type itself (`String.Empty`), the enum's members, `await` of a task of it, `new T()`, `null`
     * / `default` / `true` / `false`; at `new |` the types that fit. Null when the expected type is not known: the usual list then.
     */
    fun smartItems(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement>? {
        val analysis = Analysis(place, file)
        if (place.kind == NativeCompletionKind.TYPE) {
            if (analysis.typeRole != TypeRole.NEW && analysis.typeRole != TypeRole.EXCEPTION_ONLY) return null
            analysis.expectedOfType() ?: return null
            return smartTypes(analysis, matcher)
        }
        if (place.kind != NativeCompletionKind.EXPRESSION) return null
        val expected = analysis.expected ?: return null
        val r = analysis.resolver
        val result = ArrayList<LookupElement>()
        enumRows(analysis)?.let(result::addAll)
        if (analysis.pattern) return result
        val text = CSharpSymbolText(r)
        val at = analysis.at
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, place.leaf)) {
            ProgressManager.checkCanceled()
            if (symbol.kind == LocalSymbolKind.TYPE_PARAMETER || symbol.kind == LocalSymbolKind.LABEL || symbol.kind == LocalSymbolKind.LOCAL_FUNCTION) continue
            if (!matcher.prefixMatches(symbol.name)) continue
            val type = r.valueType(CSharpSymbol.Local(symbol)) ?: continue
            if (!analysis.fits(type, expected)) continue
            val icon = if (symbol.kind == LocalSymbolKind.LOCAL) AllIcons.Nodes.Variable else AllIcons.Nodes.Parameter
            result += prioritized(LookupElementBuilder.create(symbol.name).withIcon(icon).withTypeText(type.minimalDisplay), NativeCSharpCompletion.LOCAL)
        }
        val static = NativeCSharpLocals.inStaticContext(at)
        for (info in r.syntax.enclosingTypes(at)) for ((key, member) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || member.nestedType != null || !matcher.prefixMatches(key)) continue
            if (static && !NativeCSharpMembers.isStatic(member)) continue
            val symbols = r.membersNamed(r.selfType(info), key, 0).ifEmpty { continue }
            val type = r.valueType(symbols.first()) ?: continue
            if (!analysis.fits(type, expected)) continue
            result += memberRow(key, symbols, type, text, r, null)
        }
        // `String.Empty`, `Int32.MaxValue`, `TimeSpan.FromSeconds`: what the expected type gives of itself
        if (expected !is SemanticType.ArrayOf && analysis.expectedEnum == null && (expected as? SemanticType.Library)?.arguments.isNullOrEmpty()) {
            val name = place.name
            if (name != null) for (entry in CSharpMemberLookup(r).entries(CSharpNameResolver.Qualifier.Type(expected), name)) {
                ProgressManager.checkCanceled()
                val symbol = entry.first
                if (symbol is CSharpSymbol.SourceType || symbol is CSharpSymbol.LibraryType || symbol is CSharpSymbol.Namespace) continue
                val type = r.valueType(symbol) ?: continue
                if (!analysis.fits(type, expected)) continue
                result += memberRow(entry.name, entry.symbols, type, text, r, expected.name)
            }
        }
        result += awaitRows(analysis, expected)
        newRow(analysis, expected)?.let(result::add)
        val keywords = ArrayList<Pair<String, String?>>()
        if (r.definitionName(expected) == "System.Boolean") keywords += listOf("true" to "bool", "false" to "bool")
        if (!r.isValueType(expected) || r.isNullable(expected)) keywords += "null" to null
        keywords += "default" to null
        for ((keyword, type) in keywords) result += prioritized(LookupElementBuilder.create(keyword).bold().withTypeText(type), NativeCSharpCompletion.KEYWORD)
        return result
    }

    private fun memberRow(name: String, symbols: List<CSharpSymbol>, type: SemanticType, text: CSharpSymbolText, r: CSharpNameResolver, qualifier: String?): LookupElement {
        val method = r.isMethod(symbols.first())
        val lookup = if (qualifier != null) "$qualifier.$name" else name
        var builder = LookupElementBuilder.create(lookup).withTypeText(type.minimalDisplay)
            .withIcon(if (method) AllIcons.Nodes.Method else AllIcons.Nodes.Property)
        if (qualifier != null) builder = builder.withLookupStrings(setOf(lookup, name))
        if (method) {
            builder = builder.withTailText("()", true).withInsertHandler(NativeCSharpCalls.callHandler { symbols.all(text::returnsNothing) to symbols.any { r.signature(it, false)?.isNotEmpty() != false } })
        }
        return prioritized(builder, if (qualifier != null) NativeCSharpCompletion.METHOD else if (method) NativeCSharpCompletion.METHOD else NativeCSharpCompletion.VALUE_MEMBER)
    }

    /** Smart completion at `new |` / `throw new |`: the expected type and the types that fit it, of the solution and of the imports. */
    private fun smartTypes(analysis: Analysis, matcher: PrefixMatcher): List<LookupElement> {
        val result = ArrayList<LookupElement>()
        result += typeItems(analysis)
        val r = analysis.resolver
        val names = HashSet<String>()
        result.forEach { names += it.lookupString }
        for ((name, arities) in NativeCSharpTypeNames.candidates(analysis.file, matcher, r.syntax)) {
            if (0 !in arities || name in names) continue
            for (info in r.syntax.visibleTypes(analysis.at, name, 0)) {
                if (analysis.verdict(info) != Verdict.FITS) continue
                val namespace = info.qualifiedName.substringBeforeLast('.', "")
                val builder = LookupElementBuilder.create(name).withPresentableText("$name()").withIcon(AllIcons.Nodes.Class)
                    .withTailText(if (namespace.isEmpty()) null else " ($namespace)", true).withInsertHandler(NativeCSharpCalls.typeHandler(generic = false, constructed = true))
                result += prioritized(builder, NativeCSharpCompletion.TYPE + NativeCSharpCompletion.EXPECTED_TYPE)
                names += name
            }
        }
        return result
    }

    private fun prioritized(builder: LookupElementBuilder, priority: Double): LookupElement {
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    /** The expected type as the ranking of the native list compares it (by its text), where the tree alone does not say it. */
    fun rankingExpected(analysis: Analysis): NativeCSharpExpected? {
        if (analysis.place.kind != NativeCompletionKind.EXPRESSION || analysis.pattern) return null
        return analysis.expected?.minimalDisplay?.let { NativeCSharpExpected(it, null) }
    }

    private const val EXCEPTION = "System.Exception"

    // value types C# writes as literals: `new int()` is never what is meant
    private val NOT_CREATED = setOf(
        "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Decimal", "System.Double", "System.Single", "System.Int16", "System.Int32",
        "System.Int64", "System.UInt16", "System.UInt32", "System.UInt64", "System.IntPtr", "System.UIntPtr", "System.String",
    )
}
