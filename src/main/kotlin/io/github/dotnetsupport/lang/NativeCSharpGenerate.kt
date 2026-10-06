package io.github.dotnetsupport.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpTypeDisplay
import io.github.dotnetsupport.lang.semantic.CSharpTypeFacts
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.msbuild.CompilationModel
import javax.swing.Icon

/*
 * Rider's generators of Generate (Alt+Insert) on csharp-psi's tree and the semantics of the plugin (CSHARP_PSI_MIGRATION.md, task C4d),
 * without the language server: Constructor, Read-only properties, Properties, Missing members, Overriding members, Delegating members,
 * Partial members, Deconstructor, Equality members, Equality comparer, Relational members, Relational comparer, Formatting members,
 * Dispose pattern. A generator lists what can be chosen ([CSharpGenerateChoice], the
 * rows of the member chooser) and writes the members as text ([CSharpGeneratedCode]); [NativeCSharpGenerateEdits] puts them into the
 * type, adds the base types and the `using` directives the text needs. The texts are Rider's defaults (block bodies, `HashCode.Combine`,
 * `$"{nameof(X)}: {X}"`), formatted afterwards by the native formatter.
 */

/** Rider's generators, in the order of its list; [title] is the row of Generate, [chooserTitle] the title of the member chooser. */
enum class CSharpGenerator(val title: String, val chooserTitle: String, val icon: Icon) {
    CONSTRUCTOR("Constructor", "Generate Constructor", AllIcons.Nodes.Method),
    READ_ONLY_PROPERTIES("Read-only properties", "Generate Read-only Properties", AllIcons.Nodes.Property),
    PROPERTIES("Properties", "Generate Properties", AllIcons.Nodes.Property),
    MISSING_MEMBERS("Missing members", "Implement Missing Members", AllIcons.Gutter.ImplementingMethod),
    OVERRIDING_MEMBERS("Overriding members", "Override Members", AllIcons.Gutter.OverridingMethod),
    DELEGATING_MEMBERS("Delegating members", "Generate Delegating Members", AllIcons.Nodes.Method),
    PARTIAL_MEMBERS("Partial members", "Generate Partial Members", AllIcons.Nodes.Method),
    DECONSTRUCTOR("Deconstructor", "Generate Deconstructor", AllIcons.Nodes.Method),
    EQUALITY_MEMBERS("Equality members", "Generate Equality Members", AllIcons.Nodes.Method),
    EQUALITY_COMPARER("Equality comparer", "Generate Equality Comparer", AllIcons.Nodes.Class),
    RELATIONAL_MEMBERS("Relational members", "Generate Relational Members", AllIcons.Nodes.Method),
    RELATIONAL_COMPARER("Relational comparer", "Generate Relational Comparer", AllIcons.Nodes.Class),
    FORMATTING_MEMBERS("Formatting members", "Generate Formatting Members", AllIcons.Nodes.Method),
    DISPOSE_PATTERN("Dispose pattern", "Generate Dispose Pattern", AllIcons.Nodes.Method),
}

/**
 * Where Generate was invoked: the type around the caret (a class, struct or record with a body), its semantics, and where the members
 * go — after the member the caret is in, else after the member before the caret (after `{` when there is none). [nullable]: the nullable
 * context is on there (`#nullable`, the `Nullable` property of the project), so reference types of signatures keep their `?`.
 */
class CSharpGenerateSite(
    val file: CSharpFile, val type: CSharpTypeDeclaration, val resolver: CSharpNameResolver, val info: TypeInfo, val self: SemanticType.Source,
    val insertAt: Int, val afterMember: Boolean, val nullable: Boolean, val atCaretLine: Boolean = false,
) {
    val name: String get() = type.identifier?.text.orEmpty()

    /** `Order`, `Box<T>`: the type as its own members name it. */
    val selfText: String get() = name + (type.typeParameterList?.parameters?.joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() } ?: "")

    val isStruct: Boolean get() = info.kind == TypeKind.STRUCT || info.kind == TypeKind.RECORD_STRUCT
    val isRecord: Boolean get() = info.kind == TypeKind.RECORD || info.kind == TypeKind.RECORD_STRUCT
    val isStatic: Boolean get() = info.kind == TypeKind.STATIC_CLASS
    val isSealed: Boolean get() = isStruct || info.parts.any { "sealed" in it.modifiers }
    val isAbstract: Boolean get() = info.parts.any { "abstract" in it.modifiers }

    /** The declarations of all parts of the type (a partial type of several files too). */
    val parts: List<CSharpTypeDeclaration> get() = info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.ifEmpty { listOf(type) }

    fun writer(): CSharpCodeWriter = CSharpCodeWriter(resolver, type, nullable)

    companion object {
        fun at(file: CSharpFile, offset: Int): CSharpGenerateSite? {
            if (file.compilationUnit == null) return null
            val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
            val type = PsiTreeUtil.getParentOfType(leaf, CSharpTypeDeclaration::class.java, false) ?: return null
            if (type is CSharpInterfaceDeclaration || type is CSharpUnionDeclaration) return null
            val open = type.openBraceToken?.takeIf { it.textLength > 0 } ?: return null
            val close = type.closeBraceToken?.takeIf { it.textLength > 0 } ?: return null
            val resolver = CSharpSemanticSession(file.project).resolver(file)
            val info = resolver.syntax.declaredType(type) ?: return null
            val members = type.members
            // "Insert generated members: at the end" of the page of the server: after the last member, wherever the caret is
            if (CSharpGenerationOptions.atEnd) {
                val last = members.lastOrNull()
                return CSharpGenerateSite(file, type, resolver, info, resolver.selfType(info), last?.textRange?.endOffset ?: open.textRange.endOffset, last != null, nullableContext(file, offset))
            }
            val inside = offset.coerceIn(open.textRange.endOffset, close.textRange.startOffset)
            val holder = members.firstOrNull { it.textRange.startOffset < inside && inside < it.textRange.endOffset }
            // a blank line between members takes the members where the caret is, as in Rider
            val text = file.viewProvider.document?.charsSequence ?: file.text
            if (holder == null && offset in open.textRange.endOffset..close.textRange.startOffset) {
                var lineStart = offset
                while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
                var lineEnd = offset
                while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
                var next = lineEnd
                while (next < text.length && text[next].isWhitespace()) next++
                // something follows before `}` (else the members simply go after the last one)
                if (lineStart > open.textRange.endOffset && next < close.textRange.startOffset && text.subSequence(lineStart, lineEnd).isBlank())
                    return CSharpGenerateSite(file, type, resolver, info, resolver.selfType(info), lineStart, false, nullableContext(file, offset), atCaretLine = true)
            }
            val before = holder ?: members.lastOrNull { it.textRange.endOffset <= inside && offset > open.textRange.startOffset }
                ?: members.lastOrNull().takeIf { offset <= open.textRange.startOffset }
            val insertAt = before?.textRange?.endOffset ?: open.textRange.endOffset
            return CSharpGenerateSite(file, type, resolver, info, resolver.selfType(info), insertAt, before != null, nullableContext(file, offset))
        }

        /** [type] read with [resolver], members going after `{`: what the checks of CS0534 / CS0535 ask about a type. */
        fun of(type: CSharpTypeDeclaration, resolver: CSharpNameResolver): CSharpGenerateSite? {
            val open = type.openBraceToken?.takeIf { it.textLength > 0 } ?: return null
            val info = resolver.syntax.declaredType(type) ?: return null
            return CSharpGenerateSite(resolver.file, type, resolver, info, resolver.selfType(info), open.textRange.endOffset, false, nullable = true)
        }

        private val NULLABLE_DIRECTIVE = Regex("""#nullable\s+(enable|disable|restore)""")

        /** `#nullable` before [offset] decides; else the `Nullable` property of the project; a file of no project is taken as of a new one (enabled). */
        fun nullableContext(file: CSharpFile, offset: Int): Boolean {
            val directive = NULLABLE_DIRECTIVE.findAll(file.text).lastOrNull { it.range.first < offset }?.groupValues?.get(1)
            if (directive == "enable") return true
            if (directive == "disable") return false
            val project = CSharpSemanticEnvironment.projectOf(file) ?: return true
            val value = runCatching { CompilationModel.getInstance(file.project).options(project).nullable }.getOrNull()?.lowercase() ?: return false
            return value == "enable" || value == "annotations"
        }
    }
}

/** A group of rows of the member chooser: the base type a member comes from, "Fields", "Base constructor". */
class CSharpGenerateGroup(val text: String, val icon: Icon?)

/** One row of the member chooser: [render] writes it (null when a part of its signature is unknown); [selected] is checked at first. */
class CSharpGenerateChoice(
    val text: String, val icon: Icon?, val group: CSharpGenerateGroup?, val selected: Boolean, val payload: Any? = null,
    val render: ((CSharpCodeWriter, CSharpGenerateChoice) -> List<String>?)? = null,
) {
    override fun toString(): String = text
}

/** What a generator writes: the members (each a text with 4-space indents, re-indented when inserted), base types, the writer's usings. */
class CSharpGeneratedCode(val members: List<String>, val baseTypes: List<String> = emptyList(), val usings: Set<String> = emptySet())

/**
 * Types written where the generated code goes: keywords for the special types, a simple name where it means the type there or where a
 * `using` of its namespace can be added (collected in [usings]), else the full name. Library signatures keep the `?` of their nullable
 * reference types when [nullable].
 */
class CSharpCodeWriter(val resolver: CSharpNameResolver, private val site: PsiElement, val nullable: Boolean, private val qualified: Boolean = false) {
    val usings = LinkedHashSet<String>()

    fun type(type: SemanticType?): String? = CSharpTypeDisplay.display(type) { simple(it) }

    private fun simple(named: SemanticType): Boolean {
        if (qualified) return false
        if (CSharpTypeFacts.simpleNameMeans(resolver, named, site)) return true
        val (namespace, name, arity) = when (named) {
            is SemanticType.Library -> {
                val (first, firstArity) = IndexedTypeRef.segments(named.type.path).first()
                Triple(named.type.namespace, first, firstArity)
            }
            is SemanticType.Source -> {
                var outermost: PsiElement? = named.info.parts.firstOrNull()?.element()
                while (outermost?.parent is CSharpBaseTypeDeclaration) outermost = outermost.parent
                val declaration = outermost as? CSharpBaseTypeDeclaration ?: return false
                Triple(named.info.parts.firstNotNullOfOrNull { it.namespace }.orEmpty(), declaration.identifier?.text ?: return false,
                    (declaration as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.size ?: 0)
            }
            else -> return false
        }
        return importable(namespace, name, arity)
    }

    /** Whether `using [namespace];` makes [name] mean the type: nothing else answers to the name here. */
    private fun importable(namespace: String, name: String, arity: Int): Boolean {
        if (namespace.isEmpty()) return false
        if (namespace in usings) return true
        if (resolver.typeOrNamespace(site, name, arity).isNotEmpty()) return false
        usings += namespace
        return true
    }

    /** A type by its full metadata name (`System.NotImplementedException`), as code here writes it. */
    fun named(fullName: String): String {
        CSharpTypeDisplay.KEYWORDS[fullName]?.let { return it }
        resolver.libraryType(fullName)?.let { found -> type(found)?.let { return it } }
        val namespace = fullName.substringBeforeLast('.', "")
        val name = fullName.substringAfterLast('.')
        val known = resolver.typeOrNamespace(site, name, 0)
        if (known.any { (it as? CSharpSymbol.LibraryType)?.type?.fullName == fullName }) return name
        return if (known.isEmpty() && namespace.isNotEmpty()) { usings += namespace; name } else fullName
    }

    /** `void`, `Task<T>`, `string?`: a type of an assembly's signature, [typeArguments] for the type parameters of its type, [methodParameters] by name. */
    fun ref(reference: IndexedTypeRef, typeArguments: List<SemanticType?>, methodParameters: List<String>): String? {
        val question = if (nullable && reference.annotated) "?" else ""
        return when (reference) {
            is IndexedTypeRef.Named -> CSharpTypeDisplay.KEYWORDS[reference.fullName]?.let { it + question }
                ?: library(reference.fullName, emptyList())?.let { it + question }
            is IndexedTypeRef.Generic -> {
                val arguments = reference.arguments.map { ref(it, typeArguments, methodParameters) ?: return null }
                if (reference.definition.fullName == "System.Nullable`1" && arguments.size == 1) return arguments[0] + "?"
                if (reference.definition.fullName.startsWith("System.ValueTuple`") && arguments.size in 2..7) {
                    return arguments.mapIndexed { i, a -> reference.tupleNames?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { "$a $it" } ?: a }.joinToString(", ", "(", ")") + question
                }
                library(reference.definition.fullName, arguments)?.let { it + question }
            }
            is IndexedTypeRef.TypeParameter -> if (reference.ofMethod) methodParameters.getOrNull(reference.index)?.let { it + question } else {
                val argument = typeArguments.getOrNull(reference.index) ?: return null
                val text = type(argument) ?: return null
                if (question.isNotEmpty() && !resolver.isValueType(argument) && !text.endsWith("?")) "$text?" else text
            }
            is IndexedTypeRef.ArrayOf -> ref(reference.element, typeArguments, methodParameters)?.let { it + "[" + ",".repeat(reference.rank - 1) + "]" + question }
            is IndexedTypeRef.ByRef -> ref(reference.element, typeArguments, methodParameters)
            else -> null
        }
    }

    private fun library(fullName: String, arguments: List<String>): String? {
        val found = resolver.libraryType(fullName) ?: return null
        val segments = IndexedTypeRef.segments(found.type.path)
        if (segments.sumOf { it.second } != arguments.size) return null
        val prefix = if (simple(found)) "" else found.type.namespace.let { if (it.isEmpty()) "" else "$it." }
        var next = 0
        return prefix + segments.joinToString(".") { (name, arity) ->
            if (arity == 0) name else name + (0 until arity).joinToString(", ", "<", ">") { arguments[next++] }
        }
    }

    /** A type of a declaration of the solution as written in its file, the type arguments of [receiver] put in, `?` kept. */
    fun source(syntax: CSharpType?, receiver: SemanticType.Source?): String? {
        syntax ?: return null
        val owner = (syntax.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: return null
        val resolved = owner.resolveType(syntax)
        val substituted = if (resolved != null && receiver != null) resolver.substitute(resolved, receiver) else resolved
        val text = substituted?.let(::type) ?: return if (syntax.containingFile == site.containingFile || substituted == null && isPlain(syntax)) CSharpStubsText.collapse(syntax.text) else null
        val annotated = syntax is CSharpNullableType && !resolver.isValueType(substituted) && !text.endsWith("?")
        return if (annotated && nullable) "$text?" else text
    }

    /** A type written with keywords and type parameters only (`T`, `int[]`): the same text means it anywhere. */
    private fun isPlain(syntax: CSharpType): Boolean = PsiTreeUtil.findChildrenOfType(syntax, CSharpSimpleName::class.java).isEmpty() || syntax is CSharpSimpleName && syntax.text.length <= 2
}

/** A field or property of the type: what Constructor, Properties, Equality, Formatting, Deconstructor and Dispose pattern offer. */
class CSharpDataMember(
    val name: String, val typeText: String, val type: SemanticType?, val isField: Boolean, val isStatic: Boolean, val isReadOnly: Boolean,
    val hasGetter: Boolean, val hasSetter: Boolean, val isAuto: Boolean, val initialized: Boolean, val element: PsiElement,
) {
    val display: String get() = "$name: $typeText"
}

object NativeCSharpGenerate {
    private val FIELDS = CSharpGenerateGroup("Fields", AllIcons.Nodes.Field)
    private val PROPERTIES = CSharpGenerateGroup("Properties", AllIcons.Nodes.Property)
    private val BASE_CONSTRUCTOR = CSharpGenerateGroup("Base constructor", AllIcons.Nodes.Method)

    /** Options of the chooser of Equality members, Rider's names. */
    const val OPTION_EQUATABLE = "Implement 'IEquatable<T>' interface"
    const val OPTION_OPERATORS = "Overload equality operators"
    const val OPTION_COMPARABLE = "Implement non-generic 'IComparable' interface"
    const val OPTION_RELATIONAL_OPERATORS = "Overload relational operators"

    /** The rows a generator offers at [site]; empty: the generator is not available there (gray in Generate). */
    fun choices(generator: CSharpGenerator, site: CSharpGenerateSite): List<CSharpGenerateChoice> = when (generator) {
        CSharpGenerator.CONSTRUCTOR -> if (site.isStatic) emptyList() else constructorChoices(site)
        CSharpGenerator.READ_ONLY_PROPERTIES -> propertyChoices(site, readOnly = true)
        CSharpGenerator.PROPERTIES -> propertyChoices(site, readOnly = false)
        CSharpGenerator.MISSING_MEMBERS -> NativeCSharpInheritedMembers(site).missing()
        CSharpGenerator.OVERRIDING_MEMBERS -> if (site.isStatic || site.isStruct && site.isRecord) emptyList() else NativeCSharpInheritedMembers(site).overridable()
        CSharpGenerator.DELEGATING_MEMBERS -> NativeCSharpInheritedMembers(site).delegating()
        CSharpGenerator.PARTIAL_MEMBERS -> partialChoices(site)
        CSharpGenerator.DECONSTRUCTOR -> if (site.isStatic || has(site, "Deconstruct")) emptyList() else instanceChoices(site, all = true)
        CSharpGenerator.EQUALITY_MEMBERS -> if (site.isStatic || site.isRecord || has(site, "GetHashCode")) emptyList() else instanceChoices(site, all = false)
        CSharpGenerator.EQUALITY_COMPARER, CSharpGenerator.RELATIONAL_COMPARER -> if (site.isStatic) emptyList() else instanceChoices(site, all = false)
        CSharpGenerator.RELATIONAL_MEMBERS -> if (site.isStatic || has(site, "CompareTo")) emptyList() else instanceChoices(site, all = false)
        CSharpGenerator.FORMATTING_MEMBERS -> if (site.isStatic || has(site, "ToString")) emptyList() else instanceChoices(site, all = false)
        CSharpGenerator.DISPOSE_PATTERN -> if (site.isStatic || has(site, "Dispose")) emptyList() else disposeChoices(site)
    }

    /** Whether the chooser may be confirmed with nothing checked (a constructor without parameters, Dispose that disposes nothing). */
    fun allowsEmpty(generator: CSharpGenerator): Boolean = generator == CSharpGenerator.CONSTRUCTOR || generator == CSharpGenerator.DISPOSE_PATTERN

    fun options(generator: CSharpGenerator, site: CSharpGenerateSite): List<String> = when (generator) {
        CSharpGenerator.EQUALITY_MEMBERS -> listOfNotNull(OPTION_EQUATABLE.takeIf { !implements(site, "IEquatable") }, OPTION_OPERATORS)
        CSharpGenerator.RELATIONAL_MEMBERS -> listOfNotNull(OPTION_COMPARABLE.takeIf { !implementsNonGeneric(site, "IComparable") }, OPTION_RELATIONAL_OPERATORS)
        else -> emptyList()
    }

    /** The members [generator] writes for the [chosen] rows, with [options] checked; null when nothing can be written. */
    fun generate(generator: CSharpGenerator, site: CSharpGenerateSite, chosen: List<CSharpGenerateChoice>, options: Set<String> = emptySet()): CSharpGeneratedCode? {
        val writer = site.writer()
        val code = when (generator) {
            CSharpGenerator.CONSTRUCTOR -> constructor(site, writer, chosen)
            CSharpGenerator.READ_ONLY_PROPERTIES -> properties(chosen, readOnly = true)
            CSharpGenerator.PROPERTIES -> properties(chosen, readOnly = false)
            CSharpGenerator.MISSING_MEMBERS, CSharpGenerator.OVERRIDING_MEMBERS, CSharpGenerator.PARTIAL_MEMBERS, CSharpGenerator.DELEGATING_MEMBERS -> rendered(writer, chosen)
            CSharpGenerator.EQUALITY_COMPARER -> NativeCSharpComparers.equalityComparer(site, writer, members(chosen))
            CSharpGenerator.RELATIONAL_MEMBERS -> NativeCSharpComparers.relational(site, writer, members(chosen), options)
            CSharpGenerator.RELATIONAL_COMPARER -> NativeCSharpComparers.relationalComparer(site, writer, members(chosen))
            CSharpGenerator.DECONSTRUCTOR -> deconstructor(chosen)
            CSharpGenerator.EQUALITY_MEMBERS -> equality(site, writer, chosen, options)
            CSharpGenerator.FORMATTING_MEMBERS -> formatting(chosen)
            CSharpGenerator.DISPOSE_PATTERN -> dispose(site, writer, chosen)
        } ?: return null
        if (code.members.isEmpty()) return null
        return CSharpGeneratedCode(code.members, code.baseTypes, code.usings + writer.usings)
    }

    // ---- the members of the type

    fun dataMembers(site: CSharpGenerateSite): List<CSharpDataMember> {
        val writer = site.writer()
        val result = ArrayList<CSharpDataMember>()
        for (part in site.parts) for (member in part.members) {
            val modifiers = member.modifiers.map { it.text }.toSet()
            val static = "static" in modifiers || "const" in modifiers
            when (member) {
                is CSharpFieldDeclaration -> {
                    if ("const" in modifiers) continue
                    val declaration = member.declaration ?: continue
                    val text = typeText(writer, declaration.type, site) ?: continue
                    val type = declaration.type?.let { resolverOf(site, it)?.resolveType(it) }
                    for (variable in declaration.variables) {
                        val name = variable.identifier?.text ?: continue
                        result += CSharpDataMember(name, text, type, true, static, "readonly" in modifiers, true, "readonly" !in modifiers, true, variable.initializer != null, variable)
                    }
                }
                is CSharpPropertyDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val name = member.identifier?.text ?: continue
                    val text = typeText(writer, member.type, site) ?: continue
                    val accessors = member.accessorList?.accessors.orEmpty()
                    val keywords = accessors.mapNotNull { it.keyword?.text }
                    val auto = member.expressionBody == null && accessors.isNotEmpty() && accessors.all { it.body == null && it.expressionBody == null }
                    val type = member.type?.let { resolverOf(site, it)?.resolveType(it) }
                    result += CSharpDataMember(name, text, type, false, static, false, member.expressionBody != null || "get" in keywords,
                        "set" in keywords || "init" in keywords, auto, member.initializer != null, member)
                }
                else -> {}
            }
        }
        return result
    }

    private fun resolverOf(site: CSharpGenerateSite, element: PsiElement): CSharpNameResolver? = (element.containingFile as? CSharpFile)?.let(site.resolver.session::reachable)

    private fun typeText(writer: CSharpCodeWriter, type: CSharpType?, site: CSharpGenerateSite): String? {
        type ?: return null
        return if (type.containingFile == site.file) CSharpStubsText.collapse(type.text) else writer.source(type, null)
    }

    /** The names of the members of the type (all parts): what a new member must not repeat. */
    fun memberNames(site: CSharpGenerateSite): Set<String> {
        val names = HashSet<String>()
        for (part in site.parts) for (member in part.members) when (member) {
            is CSharpBaseFieldDeclaration -> member.declaration?.variables?.forEach { v -> v.identifier?.text?.let(names::add) }
            else -> CSharpDeclarationNames.nameElement(member)?.text?.let(names::add)
        }
        site.type.parameterList?.parameters?.forEach { p -> p.identifier?.text?.let(names::add) }
        return names
    }

    private fun has(site: CSharpGenerateSite, method: String): Boolean = site.parts.any { part ->
        part.members.any { it is CSharpMethodDeclaration && it.identifier?.text == method && it.explicitInterfaceSpecifier == null }
    }

    private fun implements(site: CSharpGenerateSite, simpleName: String): Boolean =
        site.parts.any { part -> part.baseList?.types.orEmpty().any { base -> base.type?.let { TypePart.simpleName(it)?.first } == simpleName } }

    /** `IComparable` itself listed, not `IComparable<T>`. */
    private fun implementsNonGeneric(site: CSharpGenerateSite, simpleName: String): Boolean =
        site.parts.any { part -> part.baseList?.types.orEmpty().any { base -> base.type?.let { TypePart.simpleName(it) } == (simpleName to 0) } }

    private fun members(chosen: List<CSharpGenerateChoice>): List<CSharpDataMember> = chosen.mapNotNull { it.payload as? CSharpDataMember }

    // ---- constructor

    private fun constructorChoices(site: CSharpGenerateSite): List<CSharpGenerateChoice> {
        val result = ArrayList<CSharpGenerateChoice>()
        for (member in dataMembers(site)) {
            if (member.isStatic) continue
            // a property with a body has nothing to assign to, unless it has a setter
            if (!member.isField && !member.isAuto && !member.hasSetter) continue
            result += CSharpGenerateChoice(member.display, if (member.isField) AllIcons.Nodes.Field else AllIcons.Nodes.Property,
                if (member.isField) FIELDS else PROPERTIES, selected = !member.initialized && (member.isReadOnly || !member.isField && !member.hasSetter), member)
        }
        val bases = baseConstructors(site)
        if (bases.none { it.second.isEmpty() } && bases.isNotEmpty()) {
            val fewest = bases.minByOrNull { it.second.size }
            for (base in bases) {
                result += CSharpGenerateChoice(site.resolver.let { _ -> "base(" + base.second.joinToString(", ") { "${it.second} ${it.first}" } + ")" },
                    AllIcons.Nodes.Method, BASE_CONSTRUCTOR, selected = base == fewest, base)
            }
        }
        // a constructor without parameters is a row too: Generate offers it on a type without fields
        if (result.isEmpty() && !hasConstructor(site, 0)) result += CSharpGenerateChoice("(no parameters)", AllIcons.Nodes.Method, null, selected = false, Unit)
        return result
    }

    private fun hasConstructor(site: CSharpGenerateSite, parameters: Int): Boolean =
        site.parts.any { part -> part.members.any { it is CSharpConstructorDeclaration && "static" !in it.modifiers.map { m -> m.text } && (it.parameterList?.parameters?.size ?: 0) == parameters } }

    /** The constructors of the base class: parameters as (name, type written here). Empty when the base has none of its own (`object`). */
    fun baseConstructors(site: CSharpGenerateSite): List<Pair<String, List<Pair<String, String>>>> {
        val writer = site.writer()
        val base = site.resolver.baseTypes(site.self).firstOrNull { !isInterface(it) } ?: return emptyList()
        val result = ArrayList<Pair<String, List<Pair<String, String>>>>()
        when (base) {
            is SemanticType.Source -> for (part in base.info.parts) {
                val declaration = part.element() as? CSharpTypeDeclaration ?: continue
                declaration.parameterList?.let { list -> parameters(writer, list, base)?.let { result += "primary" to it } }
                for (member in declaration.members) {
                    if (member !is CSharpConstructorDeclaration) continue
                    val modifiers = member.modifiers.map { it.text }
                    if ("static" in modifiers || "private" in modifiers && "protected" !in modifiers) continue
                    parameters(writer, member.parameterList ?: continue, base)?.let { result += member.text.take(40) to it }
                }
            }
            is SemanticType.Library -> for (member in base.type.members) {
                if (member.kind != IndexedMemberKind.CONSTRUCTOR || member.isStatic) continue
                val parameters = member.parameters.map { p -> escape(p.name) to (writer.ref(p.typeRef, base.arguments, emptyList()) ?: return@map null) }
                if (parameters.any { it == null }) continue
                @Suppress("UNCHECKED_CAST")
                result += member.docId to (parameters as List<Pair<String, String>>)
            }
            else -> {}
        }
        return result
    }

    private fun parameters(writer: CSharpCodeWriter, list: CSharpBaseParameterList, receiver: SemanticType.Source?): List<Pair<String, String>>? =
        list.parameters.map { p -> (p.identifier?.text ?: return null) to (writer.source(p.type, receiver) ?: return null) }

    fun isInterface(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.INTERFACE
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.INTERFACE
        else -> false
    }

    /** `_firstName`, `m_name` → `firstName`; `Name` → `name`; a keyword gets `@`. */
    fun parameterName(member: String): String {
        val bare = member.removePrefix("@").let { if (it.length > 2 && it[1] == '_' && it[0] in "mst") it.substring(2) else it }.trimStart('_').ifEmpty { member }
        val camel = bare.replaceFirstChar { it.lowercaseChar() }
        return escape(camel)
    }

    /** `_firstName` → `FirstName`. */
    fun propertyName(field: String): String {
        val bare = field.removePrefix("@").let { if (it.length > 2 && it[1] == '_' && it[0] in "mst") it.substring(2) else it }.trimStart('_').ifEmpty { field }
        return bare.replaceFirstChar { it.uppercaseChar() }
    }

    fun escape(name: String): String = if (name in NativeCSharpCompletionPlace.RESERVED) "@$name" else name

    @Suppress("UNCHECKED_CAST")
    private fun constructor(site: CSharpGenerateSite, writer: CSharpCodeWriter, chosen: List<CSharpGenerateChoice>): CSharpGeneratedCode {
        val members = chosen.mapNotNull { it.payload as? CSharpDataMember }
        val base = chosen.firstNotNullOfOrNull { it.payload as? Pair<String, List<Pair<String, String>>> }
        val taken = HashSet<String>()
        val parameters = ArrayList<String>()
        val baseArguments = ArrayList<String>()
        base?.second?.forEach { (name, type) ->
            val unique = CSharpVariableNames.unique(name, taken).also { taken += it }
            parameters += "$type $unique"
            baseArguments += unique
        }
        val body = ArrayList<String>()
        for (member in members) {
            val name = CSharpVariableNames.unique(parameterName(member.name), taken).also { taken += it }
            parameters += "${member.typeText} $name"
            body += if (name == member.name) "this.${member.name} = $name;" else "${member.name} = $name;"
        }
        val access = if (site.isAbstract) "protected" else "public"
        val initializer = if (base != null) " : base(${baseArguments.joinToString(", ")})" else ""
        val text = "$access ${site.name}(${parameters.joinToString(", ")})$initializer\n{\n" + body.joinToString("") { "    $it\n" } + "}"
        return CSharpGeneratedCode(listOf(text))
    }

    // ---- properties

    private fun propertyChoices(site: CSharpGenerateSite, readOnly: Boolean): List<CSharpGenerateChoice> {
        val taken = memberNames(site)
        return dataMembers(site).filter { it.isField && (readOnly || !it.isReadOnly) }.filter { field ->
            val property = propertyName(field.name)
            property != field.name && property !in taken
        }.map { CSharpGenerateChoice(it.display, AllIcons.Nodes.Field, FIELDS, selected = true, it) }
    }

    private fun properties(chosen: List<CSharpGenerateChoice>, readOnly: Boolean): CSharpGeneratedCode = CSharpGeneratedCode(chosen.mapNotNull { choice ->
        val field = choice.payload as? CSharpDataMember ?: return@mapNotNull null
        val static = if (field.isStatic) "static " else ""
        val name = propertyName(field.name)
        if (readOnly) "public $static${field.typeText} $name => ${field.name};"
        else "public $static${field.typeText} $name\n{\n    get => ${field.name};\n    set => ${field.name} = value;\n}"
    })

    // ---- members of instances: equality, formatting, deconstruction, disposal

    /** Instance fields and properties with a getter; [all]: the computed properties checked too (else only fields and auto-properties). */
    private fun instanceChoices(site: CSharpGenerateSite, all: Boolean): List<CSharpGenerateChoice> = dataMembers(site).filter { !it.isStatic && it.hasGetter }.map {
        CSharpGenerateChoice(it.display, if (it.isField) AllIcons.Nodes.Field else AllIcons.Nodes.Property, if (it.isField) FIELDS else PROPERTIES,
            selected = all || it.isField || it.isAuto, it)
    }

    private fun deconstructor(chosen: List<CSharpGenerateChoice>): CSharpGeneratedCode {
        val members = chosen.mapNotNull { it.payload as? CSharpDataMember }
        val taken = HashSet<String>()
        val names = members.map { CSharpVariableNames.unique(parameterName(it.name), taken).also { n -> taken += n } }
        val parameters = members.indices.joinToString(", ") { "out ${members[it].typeText} ${names[it]}" }
        val body = members.indices.joinToString("") { "    ${names[it]} = ${members[it].name};\n" }
        return CSharpGeneratedCode(listOf("public void Deconstruct($parameters)\n{\n$body}"))
    }

    private fun formatting(chosen: List<CSharpGenerateChoice>): CSharpGeneratedCode {
        val members = chosen.mapNotNull { it.payload as? CSharpDataMember }
        val text = members.joinToString(", ") { "{nameof(${it.name})}: {${it.name}}" }
        return CSharpGeneratedCode(listOf("public override string ToString()\n{\n    return \$\"$text\";\n}"))
    }

    /** How a member is compared: `==` for numbers but floating ones, enums, strings and `bool`; `.Equals` for other values; `Equals(a, b)` else. */
    private fun comparison(writer: CSharpCodeWriter, member: CSharpDataMember): String = comparison(writer, member, member.name, "other.${member.name}")

    /** [comparison] of the member read as [left] and [right] (`x.Name`, `y.Name` in a comparer). */
    fun comparison(writer: CSharpCodeWriter, member: CSharpDataMember, left: String, right: String): String {
        val type = member.type
        val full = (type as? SemanticType.Library)?.type?.fullName
        return when {
            // the keyword answers without the assemblies too (packages not restored yet)
            full in EQUALS_OPERATOR || member.typeText in EQUALS_KEYWORDS || type is SemanticType.Library && type.type.kind == IndexedTypeKind.ENUM || type is SemanticType.Source && type.info.kind == TypeKind.ENUM -> "$left == $right"
            type != null && writer.resolver.isValueType(type) -> "$left.Equals($right)"
            else -> "Equals($left, $right)"
        }
    }

    private val EQUALS_OPERATOR = setOf(
        "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32", "System.Int64",
        "System.UInt64", "System.Decimal", "System.String", "System.IntPtr", "System.UIntPtr",
    )
    private val EQUALS_KEYWORDS = setOf("bool", "byte", "sbyte", "char", "short", "ushort", "int", "uint", "long", "ulong", "decimal", "string", "nint", "nuint")

    private fun equality(site: CSharpGenerateSite, writer: CSharpCodeWriter, chosen: List<CSharpGenerateChoice>, options: Set<String>): CSharpGeneratedCode {
        val members = chosen.mapNotNull { it.payload as? CSharpDataMember }
        val self = site.selfText
        val q = if (site.nullable) "?" else ""
        val compared = members.joinToString(" && ") { comparison(writer, it) }.ifEmpty { "true" }
        val equatable = OPTION_EQUATABLE in options
        val result = ArrayList<String>()
        if (site.isStruct) {
            result += "public bool Equals($self other)\n{\n    return $compared;\n}"
            result += "public override bool Equals(object$q obj)\n{\n    return obj is $self other && Equals(other);\n}"
        } else {
            result += if (equatable) "public bool Equals($self$q other)\n{\n    if (other is null) return false;\n    if (ReferenceEquals(this, other)) return true;\n    return $compared;\n}"
            else "protected bool Equals($self other)\n{\n    return $compared;\n}"
            result += "public override bool Equals(object$q obj)\n{\n    if (obj is null) return false;\n    if (ReferenceEquals(this, obj)) return true;\n" +
                "    if (obj.GetType() != GetType()) return false;\n    return Equals(($self)obj);\n}"
        }
        result += "public override int GetHashCode()\n{\n" + hashCode(writer, members) + "}"
        if (OPTION_OPERATORS in options) {
            val operand = if (site.isStruct) self else "$self$q"
            val (equal, notEqual) = if (site.isStruct) "left.Equals(right)" to "!left.Equals(right)" else "Equals(left, right)" to "!Equals(left, right)"
            result += "public static bool operator ==($operand left, $operand right)\n{\n    return $equal;\n}"
            result += "public static bool operator !=($operand left, $operand right)\n{\n    return $notEqual;\n}"
        }
        val bases = if (equatable) listOf(writer.named("System.IEquatable`1").substringBefore('`').let { "$it<$self>" }) else emptyList()
        return CSharpGeneratedCode(result, bases)
    }

    /** `HashCode.Combine(a, b)` (a `HashCode` and its `Add` over 8 members); without `System.HashCode` (.NET Framework) Rider's `* 397` form. */
    fun hashCode(writer: CSharpCodeWriter, members: List<CSharpDataMember>, prefix: String = ""): String {
        if (members.isEmpty()) return "    return 0;\n"
        val known = writer.resolver.libraryType("System.Object") != null
        if (!known || writer.resolver.libraryType("System.HashCode") != null) {
            val hashCode = writer.named("System.HashCode")
            if (members.size <= 8) return "    return $hashCode.Combine(${members.joinToString(", ") { prefix + it.name }});\n"
            return "    var hashCode = new $hashCode();\n" + members.joinToString("") { "    hashCode.Add($prefix${it.name});\n" } + "    return hashCode.ToHashCode();\n"
        }
        fun hash(member: CSharpDataMember): String {
            val type = member.type
            val full = (type as? SemanticType.Library)?.type?.fullName
            val name = prefix + member.name
            return when {
                full == "System.Int32" -> name
                full == "System.Boolean" || type != null && writer.resolver.isValueType(type) -> "$name.GetHashCode()"
                else -> "($name != null ? $name.GetHashCode() : 0)"
            }
        }
        if (members.size == 1) return "    return ${hash(members[0])};\n"
        return "    unchecked\n    {\n        var hashCode = ${hash(members[0])};\n" +
            members.drop(1).joinToString("") { "        hashCode = (hashCode * 397) ^ ${hash(it)};\n" } + "        return hashCode;\n    }\n"
    }

    private fun disposeChoices(site: CSharpGenerateSite): List<CSharpGenerateChoice> {
        val disposable = dataMembers(site).filter { !it.isStatic && it.isField && CSharpTypeFacts.implements(site.resolver, it.type, CSharpTypeFacts.DISPOSABLE) == true }
        val rows = disposable.map { CSharpGenerateChoice(it.display, AllIcons.Nodes.Field, FIELDS, selected = true, it) }
        // the pattern itself is worth generating without a disposable field: a row that stands for it
        return rows.ifEmpty { listOf(CSharpGenerateChoice("(no disposable fields)", AllIcons.Nodes.Method, null, selected = false, Unit)) }
    }

    private fun dispose(site: CSharpGenerateSite, writer: CSharpCodeWriter, chosen: List<CSharpGenerateChoice>): CSharpGeneratedCode {
        val fields = chosen.mapNotNull { it.payload as? CSharpDataMember }
        val calls = fields.map { field -> if (field.typeText.endsWith("?")) "${field.name}?.Dispose();" else "${field.name}.Dispose();" }
        val bases = if (CSharpTypeFacts.implements(site.resolver, site.self, CSharpTypeFacts.DISPOSABLE) == true) emptyList() else listOf(writer.named(CSharpTypeFacts.DISPOSABLE))
        if (site.isSealed) {
            return CSharpGeneratedCode(listOf("public void Dispose()\n{\n" + calls.joinToString("") { "    $it\n" } + "}"), bases)
        }
        val gc = writer.named("System.GC")
        val disposing = "protected virtual void Dispose(bool disposing)\n{\n    if (disposing)\n    {\n" + calls.joinToString("") { "        $it\n" } + "    }\n}"
        val public = "public void Dispose()\n{\n    Dispose(true);\n    $gc.SuppressFinalize(this);\n}"
        return CSharpGeneratedCode(listOf(disposing, public), bases)
    }

    // ---- partial members

    private fun partialChoices(site: CSharpGenerateSite): List<CSharpGenerateChoice> {
        val candidates = NativeCSharpOverrides.partialCandidates(site.type, site.resolver.syntax)
        return candidates.map { candidate ->
            CSharpGenerateChoice(candidate.name + candidate.tail, AllIcons.Nodes.Method, null, selected = true, candidate) { _, _ ->
                val access = if (candidate.access.isEmpty()) "" else candidate.access + " "
                listOf("${access}partial ${candidate.header}\n{\n}")
            }
        }
    }

    private fun rendered(writer: CSharpCodeWriter, chosen: List<CSharpGenerateChoice>): CSharpGeneratedCode =
        CSharpGeneratedCode(chosen.flatMap { choice -> choice.render?.invoke(writer, choice).orEmpty() })
}

/**
 * Missing members (abstract members of the base classes, members of the interfaces not implemented) and overridable members (virtual,
 * abstract, override of the base classes, not sealed) of a type, from the solution and from the assemblies (the index of assemblies, so
 * `IDisposable`, `IComparable<T>`, `Stream` count). Members are told apart by name, kind and the number of parameters.
 */
class NativeCSharpInheritedMembers(private val site: CSharpGenerateSite) {
    private val resolver = site.resolver
    /** Writes the labels of the chooser only: its usings are not added anywhere. */
    private val labelWriter by lazy { site.writer() }
    /** Writes full names, as Roslyn's messages do (`System.IComparable<Shop.Order>`). */
    private val qualifiedWriter by lazy { CSharpCodeWriter(resolver, site.type, site.nullable, qualified = true) }

    private enum class Kind { METHOD, PROPERTY, INDEXER, EVENT }

    /** A member of the solution or of an assembly; [abstract] etc. as declared; [key] matches it with an implementation. */
    private class Inherited(
        val name: String, val kind: Kind, val parameters: Int, val modifiers: Set<String>, val owner: SemanticType, val element: PsiElement?, val library: IndexedMember?,
    ) {
        val key: String get() = "$name/$kind/$parameters"
        val abstract: Boolean get() = "abstract" in modifiers
        val overridable: Boolean get() = ("virtual" in modifiers || "abstract" in modifiers || "override" in modifiers) && "sealed" !in modifiers && "static" !in modifiers
    }

    private fun declared(type: SemanticType): List<Inherited> = when (type) {
        is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.flatMap { part -> part.members.flatMap { sourceMember(it, type) } }
        is SemanticType.Library -> type.type.members.mapNotNull { member ->
            val kind = when (member.kind) {
                IndexedMemberKind.METHOD -> Kind.METHOD
                IndexedMemberKind.PROPERTY -> Kind.PROPERTY
                IndexedMemberKind.INDEXER -> Kind.INDEXER
                IndexedMemberKind.EVENT -> Kind.EVENT
                else -> return@mapNotNull null
            }
            if ('.' in member.name || member.name.startsWith("op_") || member.name.startsWith("<") || member.name == "Finalize") return@mapNotNull null
            val modifiers = buildSet {
                if (member.isAbstract) add("abstract")
                if (member.isVirtual) add("virtual")
                if (member.isOverride) add("override")
                if (member.isSealed) add("sealed")
                if (member.isStatic) add("static")
                add(if (member.isProtected) "protected" else "public")
            }
            Inherited(if (kind == Kind.INDEXER) "this" else member.name, kind, if (kind == Kind.METHOD || kind == Kind.INDEXER) member.parameters.size else 0, modifiers, type, null, member)
        }
        else -> emptyList()
    }

    private fun sourceMember(member: CSharpMemberDeclaration, owner: SemanticType): List<Inherited> {
        val interfaceMember = owner is SemanticType.Source && owner.info.kind == TypeKind.INTERFACE
        val modifiers = member.modifiers.map { it.text }.toMutableSet()
        fun withBody(hasBody: Boolean) {
            // a member of an interface without a body is abstract; one with a body has a default implementation
            if (interfaceMember && !hasBody && "static" !in modifiers) modifiers += "abstract"
        }
        return when (member) {
            is CSharpMethodDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return emptyList()
                withBody(member.body != null || member.expressionBody != null)
                listOf(Inherited(member.identifier?.text ?: return emptyList(), Kind.METHOD, member.parameterList?.parameters?.size ?: 0, modifiers, owner, member, null))
            }
            is CSharpPropertyDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return emptyList()
                withBody(member.expressionBody != null || member.accessorList?.accessors.orEmpty().any { it.body != null || it.expressionBody != null })
                listOf(Inherited(member.identifier?.text ?: return emptyList(), Kind.PROPERTY, 0, modifiers, owner, member, null))
            }
            is CSharpIndexerDeclaration -> {
                withBody(member.expressionBody != null || member.accessorList?.accessors.orEmpty().any { it.body != null || it.expressionBody != null })
                listOf(Inherited("this", Kind.INDEXER, member.parameterList?.parameters?.size ?: 0, modifiers, owner, member, null))
            }
            is CSharpEventFieldDeclaration -> {
                withBody(false)
                member.declaration?.variables.orEmpty().mapNotNull { v -> v.identifier?.text?.let { Inherited(it, Kind.EVENT, 0, modifiers, owner, v, null) } }
            }
            is CSharpEventDeclaration -> {
                withBody(member.accessorList?.accessors.orEmpty().any { it.body != null })
                listOf(Inherited(member.identifier?.text ?: return emptyList(), Kind.EVENT, 0, modifiers, owner, member, null))
            }
            else -> emptyList()
        }
    }

    /** The base classes, the nearest first, with the type arguments the type gives them. */
    private fun classChain(): List<SemanticType> {
        val result = ArrayList<SemanticType>()
        var current: SemanticType = site.self
        val seen = HashSet<String>()
        while (result.size < 32) {
            when (current) {
                is SemanticType.Source -> {
                    val base = resolver.baseTypes(current).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: break
                    if (!seen.add(base.toString())) break
                    result += base
                    current = base
                }
                is SemanticType.Library -> {
                    for (supertype in resolver.session.baseTypes(resolver.assemblies, current.type)) {
                        resolver.fromRef(supertype.reference, current.arguments)?.let { result += it }
                    }
                    break
                }
                else -> break
            }
        }
        return result
    }

    /** The interfaces the type itself lists, with theirs, each once. */
    private fun interfaces(): List<SemanticType> {
        val result = LinkedHashMap<String, SemanticType>()
        fun visit(type: SemanticType, depth: Int) {
            if (depth > 16) return
            val key = CSharpTypeDisplay.display(type) ?: type.toString()
            if (result.putIfAbsent(key, type) != null) return
            when (type) {
                is SemanticType.Source -> resolver.baseTypes(type).filter(NativeCSharpGenerate::isInterface).forEach { visit(it, depth + 1) }
                is SemanticType.Library -> resolver.session.interfaces(resolver.assemblies, type.type).forEach { s -> resolver.fromRef(s.reference, type.arguments)?.let { visit(it, depth + 1) } }
                else -> {}
            }
        }
        resolver.baseTypes(site.self).filter(NativeCSharpGenerate::isInterface).forEach { visit(it, 0) }
        return result.values.toList()
    }

    /** Keys of the members the type declares (`override` ones apart) and of its explicit implementations (`IFoo:key`). */
    private fun own(): Triple<Set<String>, Set<String>, Set<String>> {
        val all = HashSet<String>()
        val overrides = HashSet<String>()
        val explicit = HashSet<String>()
        for (part in site.parts) for (member in part.members) {
            val specifier = when (member) {
                is CSharpMethodDeclaration -> member.explicitInterfaceSpecifier
                is CSharpBasePropertyDeclaration -> member.explicitInterfaceSpecifier
                else -> null
            }
            if (specifier != null) {
                val interfaceName = specifier.nameElement?.let { TypePart.simpleName(it)?.first ?: it.text }
                val copy = when (member) {
                    is CSharpMethodDeclaration -> member.identifier?.text?.let { "$it/${Kind.METHOD}/${member.parameterList?.parameters?.size ?: 0}" }
                    is CSharpPropertyDeclaration -> member.identifier?.text?.let { "$it/${Kind.PROPERTY}/0" }
                    is CSharpIndexerDeclaration -> "this/${Kind.INDEXER}/${member.parameterList?.parameters?.size ?: 0}"
                    is CSharpEventDeclaration -> member.identifier?.text?.let { "$it/${Kind.EVENT}/0" }
                    else -> null
                }
                if (interfaceName != null && copy != null) explicit += "$interfaceName:$copy"
                continue
            }
            for (inherited in sourceMember(member, site.self)) {
                all += inherited.key
                if ("override" in inherited.modifiers) overrides += inherited.key
            }
        }
        return Triple(all, overrides, explicit)
    }

    /** One group per base type: the chooser puts the rows of one group object under one node. */
    private val groups = HashMap<String, CSharpGenerateGroup>()

    private fun group(type: SemanticType): CSharpGenerateGroup {
        val text = CSharpTypeDisplay.display(type, qualified = false) ?: type.name
        return groups.getOrPut((CSharpTypeDisplay.display(type) ?: text) + "/" + text) {
            CSharpGenerateGroup(text, if (NativeCSharpGenerate.isInterface(type)) AllIcons.Nodes.Interface else AllIcons.Nodes.Class)
        }
    }

    fun missing(): List<CSharpGenerateChoice> = gaps(forErrors = false).map { choice(it.member, group(it.owner), it.mode, selected = true, delegateTo = it.delegateTo) }

    /** A member the type must implement and does not: of [owner], to write in [mode]. */
    private class Gap(val member: Inherited, val owner: SemanticType, val mode: Mode, val delegateTo: Inherited?)

    /**
     * [forErrors]: what CS0534 / CS0535 are reported for, so only the sure part — not the abstract members of an abstract class, not an
     * interface a base class implements too (its explicit implementations there are not seen), not two interfaces with one signature.
     */
    private fun gaps(forErrors: Boolean): List<Gap> {
        val (own, overrides, explicit) = own()
        val result = ArrayList<Gap>()
        val chain = classChain()
        // abstract members of the base classes that no class on the way down overrides
        val covered = HashSet(overrides)
        if (!(forErrors && site.isAbstract)) for (base in chain) {
            val members = declared(base)
            for (member in members) if (member.abstract && "static" !in member.modifiers && member.key !in covered) {
                covered += member.key
                result += Gap(member, base, Mode.OVERRIDE_ABSTRACT, null)
            }
            members.filter { "override" in it.modifiers }.forEach { covered += it.key }
        }
        // interface members: implemented by the type, its bases, or explicitly
        val inherited = HashSet(own)
        for (base in chain) for (member in declared(base)) if ("private" !in member.modifiers) inherited += member.key
        val ofBases = if (forErrors) interfacesOfBases(chain) else emptySet()
        val implicitDone = HashMap<String, Inherited>()
        for (face in interfaces()) {
            if ((CSharpTypeDisplay.display(face) ?: face.toString()) in ofBases) continue
            val simple = face.name
            for (member in declared(face)) {
                if (!member.abstract || "static" in member.modifiers) continue
                if (member.key in inherited || "$simple:${member.key}" in explicit) continue
                val same = implicitDone[member.key]
                if (same == null) {
                    implicitDone[member.key] = member
                    result += Gap(member, face, Mode.IMPLEMENT, null)
                } else if (member.kind != Kind.EVENT && !forErrors) {
                    // the same signature from two interfaces (`IEnumerable<T>` and `IEnumerable`): the second one explicitly, calling the first
                    result += Gap(member, face, Mode.EXPLICIT, same)
                }
            }
        }
        return result
    }

    /** The interfaces the base classes implement (with their base interfaces), by their display. */
    private fun interfacesOfBases(chain: List<SemanticType>): Set<String> {
        val result = HashSet<String>()
        fun visit(type: SemanticType, depth: Int) {
            if (depth > 16 || !result.add(CSharpTypeDisplay.display(type) ?: type.toString())) return
            when (type) {
                is SemanticType.Source -> resolver.baseTypes(type).filter(NativeCSharpGenerate::isInterface).forEach { visit(it, depth + 1) }
                is SemanticType.Library -> resolver.session.interfaces(resolver.assemblies, type.type).forEach { s -> resolver.fromRef(s.reference, type.arguments)?.let { visit(it, depth + 1) } }
                else -> {}
            }
        }
        for (base in chain) when (base) {
            is SemanticType.Source -> resolver.baseTypes(base).filter(NativeCSharpGenerate::isInterface).forEach { visit(it, 0) }
            is SemanticType.Library -> resolver.session.interfaces(resolver.assemblies, base.type).forEach { s -> resolver.fromRef(s.reference, base.arguments)?.let { visit(it, 0) } }
            else -> {}
        }
        return result
    }

    /** A member CS0534 (an abstract one of a base class) or CS0535 (one of an interface, [face]) is reported for; [display] as Roslyn names it: `Shape.Area()`. */
    class MissingMember(val display: String, val face: SemanticType?)

    fun missingMembers(): List<MissingMember> = gaps(forErrors = true).map { gap ->
        val owner = CSharpTypeDisplay.display(gap.owner) ?: gap.owner.name
        val signature = signature(qualifiedWriter, gap.member, false)
        val types = signature?.declaredParameters?.let { declared ->
            CSharpScopeNames.splitTopLevel(declared).filter { it.isNotBlank() }.joinToString(", ") { p -> p.trim().substringBeforeLast(' ').removePrefix("params ") }
        }
        val display = when (gap.member.kind) {
            Kind.METHOD -> "$owner.${gap.member.name}(${types.orEmpty()})"
            Kind.INDEXER -> "$owner.this[${types.orEmpty()}]"
            else -> "$owner.${gap.member.name}"
        }
        MissingMember(display, gap.owner.takeIf { gap.mode == Mode.IMPLEMENT })
    }

    fun overridable(): List<CSharpGenerateChoice> = overridableMembers().map { (member, base) ->
        choice(member, group(base), if (member.abstract) Mode.OVERRIDE_ABSTRACT else Mode.OVERRIDE, selected = false)
    }

    /** The members of the base classes that can be overridden here and are not yet, nearest base first, each with the base it is declared in. */
    private fun overridableMembers(): List<Pair<Inherited, SemanticType>> {
        val (_, overrides, _) = own()
        val seen = HashSet<String>()
        val result = ArrayList<Pair<Inherited, SemanticType>>()
        for (base in classChain()) {
            for (member in declared(base)) {
                if (!member.overridable && !("sealed" in member.modifiers && "override" in member.modifiers)) continue
                if (!seen.add(member.key) || "sealed" in member.modifiers) continue
                if (member.key in overrides || "private" in member.modifiers && "protected" !in member.modifiers) continue
                // a record has its equality made by the compiler
                if (site.isRecord && member.name in RECORD_MEMBERS) continue
                result += member to base
            }
        }
        return result
    }

    /**
     * One item of `override |` (completion): the member as written after its modifiers ([text]: `Task<HelloReply> SayHello(...)` with
     * its body, 4-space levels), the accessibility of the base member, the namespaces the text needs, and what the list shows.
     */
    class OverrideItem(
        val name: String, val tail: String, val type: String?, val property: Boolean, val abstract: Boolean, val access: String, val text: String,
        val usings: Set<String>, val base: String,
    )

    /** What `override |` offers: [overridable] written as Rider writes them, each with its own usings. */
    fun overrideItems(): List<OverrideItem> = overridableMembers().mapNotNull { (member, base) ->
        // an event is overridden rarely: the dialog lists it, the completion does not
        if (member.kind == Kind.EVENT) return@mapNotNull null
        val writer = site.writer()
        val mode = if (member.abstract) Mode.OVERRIDE_ABSTRACT else Mode.OVERRIDE
        val head = access(member, mode)
        val rendered = render(writer, member, mode, null) ?: return@mapNotNull null
        val text = rendered.removePrefix(head)
        val firstLine = text.substringBefore('\n')
        val nameAt = if (member.kind == Kind.INDEXER) firstLine.indexOf(" this[") else firstLine.indexOf(" ${member.name}")
        val type = if (nameAt > 0) firstLine.substring(0, nameAt) else null
        val tail = when (member.kind) {
            Kind.METHOD -> if (nameAt > 0) firstLine.substring(nameAt + 1 + member.name.length) else ""
            Kind.INDEXER -> if (nameAt > 0) firstLine.substring(nameAt + " this".length) else ""
            else -> ""
        }
        OverrideItem(member.name, tail, type, member.kind != Kind.METHOD, member.abstract, head.removeSuffix("override ").trim(), text, writer.usings.toSet(),
            CSharpTypeDisplay.display(base, qualified = false) ?: base.name)
    }

    /** The interfaces the type lists (and theirs) that have members left to implement explicitly: what `void |` offers before the dot (0.1.94). */
    fun explicitInterfaces(): List<SemanticType> = interfaces().filter { explicitItems(it).isNotEmpty() }

    /** The interface [face] and the interfaces the type implements, by the way Roslyn displays them. */
    fun interfaceNamed(display: String?): SemanticType? = interfaces().firstOrNull { CSharpTypeDisplay.display(it) == display }

    /**
     * One item of `void IFoo.|` (completion): the member of [face] not implemented explicitly yet, written whole as `void IFoo.M(int a)`
     * with a body that throws; [candidate] is what [NativeCSharpOverrides.insert] writes. Members of the interface itself only (the ones
     * of its base interfaces are implemented through those), no static or event members.
     */
    class ExplicitItem(val name: String, val candidate: NativeCSharpOverrides.Candidate)

    fun explicitItems(face: SemanticType): List<ExplicitItem> {
        val (_, _, explicit) = own()
        val result = ArrayList<ExplicitItem>()
        for (member in declared(face)) {
            if ("static" in member.modifiers || "private" in member.modifiers || member.kind == Kind.EVENT) continue
            if ("${face.name}:${member.key}" in explicit) continue
            val writer = site.writer()
            val text = render(writer, member, Mode.EXPLICIT, null) ?: continue
            val prefix = (writer.type(face) ?: continue) + "."
            val lines = text.lines()
            val open = lines.indexOfFirst { it.trim() == "{" }
            val header = if (open <= 0) lines[0] else lines.subList(0, open).joinToString("\n")
            val body = if (open <= 0) null else lines.subList(open + 1, lines.size - 1).map { it.removePrefix("    ") }
            val nameAt = header.indexOf(prefix + (if (member.kind == Kind.INDEXER) "this" else member.name))
            if (nameAt < 0) continue
            val type = header.substring(0, nameAt).trim().ifEmpty { null }
            val tail = header.substring(nameAt + prefix.length + (if (member.kind == Kind.INDEXER) "this".length else member.name.length))
            val shown = CSharpTypeDisplay.display(face, qualified = false) ?: face.name
            result += ExplicitItem(member.name, NativeCSharpOverrides.Candidate(member.name, "", header, body, tail, type, member.kind != Kind.METHOD, writer.usings.toSet(), shown))
        }
        return result
    }

    /**
     * Delegating members: for each field and property of the type (the groups of the chooser, as Rider's first page), the public members
     * of its type and its bases that the type does not declare yet, each written to call the same member of the field.
     */
    fun delegating(): List<CSharpGenerateChoice> {
        val (own, _, _) = own()
        val result = ArrayList<CSharpGenerateChoice>()
        for (target in NativeCSharpGenerate.dataMembers(site)) {
            if (!target.hasGetter || site.isStatic && !target.isStatic) continue
            val type = target.type ?: continue
            val group = CSharpGenerateGroup(target.display, if (target.isField) AllIcons.Nodes.Field else AllIcons.Nodes.Property)
            val seen = HashSet<String>()
            for (holder in delegatedTypes(type)) for (member in declared(holder)) {
                if ("static" in member.modifiers || member.name in OBJECT_MEMBERS || !isPublic(member, holder)) continue
                if (member.key in own || !seen.add(member.key)) continue
                result += choice(member, group, Mode.DELEGATE, selected = false, receiver = target.name, static = target.isStatic)
            }
        }
        return result
    }

    private fun isPublic(member: Inherited, holder: SemanticType): Boolean = when {
        member.library != null -> "public" in member.modifiers
        NativeCSharpGenerate.isInterface(holder) -> "private" !in member.modifiers && "protected" !in member.modifiers
        else -> "public" in member.modifiers
    }

    /** The type a member delegates to and its bases (its base interfaces for an interface): not `object`, not what a class implements explicitly. */
    private fun delegatedTypes(type: SemanticType): List<SemanticType> {
        val result = LinkedHashMap<String, SemanticType>()
        fun visit(t: SemanticType, depth: Int) {
            if (depth > 16 || (t as? SemanticType.Library)?.type?.fullName == "System.Object") return
            val key = CSharpTypeDisplay.display(t) ?: t.toString()
            if (result.putIfAbsent(key, t) != null) return
            val face = NativeCSharpGenerate.isInterface(t)
            when (t) {
                is SemanticType.Source -> resolver.baseTypes(t).filter { face || !NativeCSharpGenerate.isInterface(it) }.forEach { visit(it, depth + 1) }
                is SemanticType.Library -> {
                    val supers = resolver.session.baseTypes(resolver.assemblies, t.type) + if (face) resolver.session.interfaces(resolver.assemblies, t.type) else emptyList()
                    for (s in supers) resolver.fromRef(s.reference, t.arguments)?.let { visit(it, depth + 1) }
                }
                else -> {}
            }
        }
        visit(type, 0)
        return result.values.toList()
    }

    private enum class Mode { IMPLEMENT, EXPLICIT, OVERRIDE_ABSTRACT, OVERRIDE, DELEGATE }

    private fun choice(
        member: Inherited, group: CSharpGenerateGroup, mode: Mode, selected: Boolean, delegateTo: Inherited? = null, receiver: String? = null, static: Boolean = false,
    ): CSharpGenerateChoice {
        val icon = when (member.kind) {
            Kind.METHOD -> AllIcons.Nodes.Method
            Kind.PROPERTY, Kind.INDEXER -> AllIcons.Nodes.Property
            Kind.EVENT -> AllIcons.Nodes.Field
        }
        val text = describe(member)
        return CSharpGenerateChoice(text, icon, group, selected, member) { writer, _ ->
            (if (mode == Mode.DELEGATE) delegate(writer, member, receiver!!, static) else render(writer, member, mode, delegateTo))?.let(::listOf)
        }
    }

    /** A member that passes everything to the same member of [receiver]: `public int Count => _items.Count;`, `public void Add(T item) { _items.Add(item); }`. */
    private fun delegate(writer: CSharpCodeWriter, member: Inherited, receiver: String, static: Boolean): String? {
        val signature = signature(writer, member, withConstraints = true, details = Details.FULL) ?: return null
        val head = if (static) "public static " else "public "
        val name = member.name
        return when (member.kind) {
            Kind.METHOD -> {
                val arguments = signature.parameters.joinToString(", ") { (modifier, parameter) -> listOfNotNull(modifier.takeIf { it.isNotEmpty() }, parameter).joinToString(" ") }
                val typeArguments = if (signature.typeParameters.isEmpty()) "" else signature.typeParameters.joinToString(", ", "<", ">")
                val call = "$receiver.$name$typeArguments($arguments);"
                "$head${signature.type} $name$typeArguments(${signature.declaredParameters})${signature.constraints}\n{\n    ${if (signature.type == "void") call else "return $call"}\n}"
            }
            Kind.PROPERTY -> {
                // an `init` accessor of the field's property cannot be called from here
                val setter = "set" in signature.accessors
                if (!setter) "$head${signature.type} $name => $receiver.$name;"
                else "$head${signature.type} $name\n{\n" + (if ("get" in signature.accessors) "    get => $receiver.$name;\n" else "") + "    set => $receiver.$name = value;\n}"
            }
            Kind.INDEXER -> {
                val arguments = signature.parameters.joinToString(", ") { it.second }
                "$head${signature.type} this[${signature.declaredParameters}]\n{\n" + signature.accessors.filter { it != "init" }.joinToString("") { a ->
                    if (a == "get") "    get => $receiver[$arguments];\n" else "    $a => $receiver[$arguments] = value;\n"
                } + "}"
            }
            Kind.EVENT -> "${head}event ${signature.type} $name\n{\n    add => $receiver.$name += value;\n    remove => $receiver.$name -= value;\n}"
        }
    }

    private fun describe(member: Inherited): String {
        val library = member.library
        // the signature as the code gets it: `CompareTo(GenMoney? other): int` of `IComparable<GenMoney>`, not `CompareTo(T)`
        if (library != null) signature(labelWriter, member, false)?.let { s ->
            return when (member.kind) {
                Kind.METHOD -> library.name + (if (s.typeParameters.isEmpty()) "" else s.typeParameters.joinToString(", ", "<", ">")) + "(" + s.declaredParameters + "): " + s.type
                Kind.INDEXER -> "this[" + s.declaredParameters + "]: " + s.type
                else -> library.name + ": " + s.type
            }
        }
        if (library != null) return when (member.kind) {
            Kind.METHOD -> library.name + library.parameters.joinToString(", ", "(", ")") { it.type } + ": " + library.returnType
            Kind.INDEXER -> "this" + library.parameters.joinToString(", ", "[", "]") { it.type } + ": " + library.returnType
            else -> library.name + ": " + library.returnType
        }
        return when (val element = member.element) {
            is CSharpMethodDeclaration -> member.name + CSharpStubsText.collapse(element.parameterList?.text ?: "()") + ": " + CSharpStubsText.collapse(element.returnType?.text.orEmpty())
            is CSharpPropertyDeclaration -> member.name + ": " + CSharpStubsText.collapse(element.type?.text.orEmpty())
            is CSharpIndexerDeclaration -> "this" + CSharpStubsText.collapse(element.parameterList?.text ?: "[]") + ": " + CSharpStubsText.collapse(element.type?.text.orEmpty())
            else -> member.name
        }
    }

    // ---- writing

    private fun access(member: Inherited, mode: Mode): String = when (mode) {
        Mode.IMPLEMENT, Mode.DELEGATE -> "public "
        Mode.EXPLICIT -> ""
        Mode.OVERRIDE, Mode.OVERRIDE_ABSTRACT -> {
            val access = ACCESS.filter { it in member.modifiers }.joinToString(" ").ifEmpty { "public" }
            "$access override "
        }
    }

    private fun throwing(writer: CSharpCodeWriter): String = "throw new ${writer.named("System.NotImplementedException")}()"

    private fun render(writer: CSharpCodeWriter, member: Inherited, mode: Mode, delegateTo: Inherited?): String? {
        // an explicit implementation gets no default values: they have no effect there (CS1066)
        val signature = signature(writer, member, mode == Mode.IMPLEMENT, if (mode == Mode.EXPLICIT) Details.ATTRIBUTES else Details.FULL) ?: return null
        val explicitPrefix = if (mode == Mode.EXPLICIT) (writer.type(member.owner) ?: return null) + "." else ""
        val head = access(member, mode)
        // only a member that throws needs `using System;`
        val throwing by lazy { throwing(writer) }
        val name = member.name
        return when (member.kind) {
            Kind.METHOD -> {
                val arguments = signature.parameters.joinToString(", ") { (modifier, parameter) -> listOfNotNull(modifier.takeIf { it.isNotEmpty() }, parameter).joinToString(" ") }
                val typeArguments = if (signature.typeParameters.isEmpty()) "" else signature.typeParameters.joinToString(", ", "<", ">")
                val void = signature.type == "void"
                val statement = when {
                    delegateTo != null -> (if (void) "" else "return ") + "$name$typeArguments($arguments);"
                    mode == Mode.OVERRIDE -> (if (void) "" else "return ") + "base.$name$typeArguments($arguments);"
                    else -> "$throwing;"
                }
                "$head${signature.type} $explicitPrefix$name$typeArguments(${signature.declaredParameters})${signature.constraints}\n{\n    $statement\n}"
            }
            Kind.PROPERTY -> {
                val accessors = signature.accessors
                when (mode) {
                    Mode.OVERRIDE -> "$head${signature.type} $name\n{\n" + accessors.joinToString("") { a ->
                        if (a == "get") "    get => base.$name;\n" else "    $a => base.$name = value;\n"
                    } + "}"
                    Mode.EXPLICIT -> "${signature.type} $explicitPrefix$name\n{\n" + accessors.joinToString("") { a -> "    $a => $throwing;\n" } + "}"
                    // "Generated properties: prefer throwing properties" of the page of the server (Roslyn's default), else an auto property
                    else -> if (CSharpGenerationOptions.throwingProperties) "$head${signature.type} $name\n{\n" + accessors.joinToString("") { a -> "    $a => $throwing;\n" } + "}"
                    else "$head${signature.type} $name { " + accessors.joinToString(" ") { "$it;" } + " }"
                }
            }
            Kind.INDEXER -> {
                val arguments = signature.parameters.joinToString(", ") { it.second }
                "$head${signature.type} ${explicitPrefix}this[${signature.declaredParameters}]\n{\n" + signature.accessors.joinToString("") { a ->
                    when {
                        mode != Mode.OVERRIDE -> "    $a => $throwing;\n"
                        a == "get" -> "    get => base[$arguments];\n"
                        else -> "    $a => base[$arguments] = value;\n"
                    }
                } + "}"
            }
            Kind.EVENT -> if (mode == Mode.EXPLICIT) null else "${head}event ${signature.type} $name;"
        }
    }

    /** A signature written at the site: type, parameters (declared and as arguments), type parameters, constraints (of an implicit implementation), accessors. */
    private class Signature(
        val type: String, val declaredParameters: String, val parameters: List<Pair<String, String>>, val typeParameters: List<String>, val constraints: String,
        val accessors: List<String>,
    )

    /** What a written parameter keeps besides modifiers, type and name: none for labels, attributes and default values (`= default`) as Rider copies them. */
    private enum class Details { NONE, ATTRIBUTES, FULL }

    private fun signature(writer: CSharpCodeWriter, member: Inherited, withConstraints: Boolean, details: Details = Details.NONE): Signature? {
        val library = member.library
        if (library != null) {
            val arguments = (member.owner as? SemanticType.Library)?.arguments.orEmpty()
            val typeParameters = if (library.arity > 0) library.typeParameters.map { it.name } else emptyList()
            val type = writer.ref(library.typeRef, arguments, typeParameters) ?: return null
            val declared = ArrayList<String>()
            val passed = ArrayList<Pair<String, String>>()
            for (p in library.parameters) {
                val modifier = when { p.isOut -> "out"; p.isRef -> "ref"; p.isIn -> "in"; else -> "" }
                val pType = writer.ref(p.typeRef, arguments, typeParameters) ?: return null
                val pName = NativeCSharpGenerate.escape(p.name)
                val default = p.defaultValue?.takeIf { details == Details.FULL && (p.hasDefault || p.isOptional) && !p.isParams }?.let { " = $it" }.orEmpty()
                declared += listOfNotNull(modifier.takeIf { it.isNotEmpty() }, "params".takeIf { p.isParams }, pType, pName).joinToString(" ") + default
                passed += modifier to pName
            }
            val constraints = if (withConstraints && library.arity > 0) libraryConstraints(writer, library, arguments, typeParameters) ?: return null else ""
            val accessors = listOfNotNull("get".takeIf { library.hasGetter || member.kind == Kind.INDEXER && !library.hasSetter }, if (library.isInitOnly) "init" else "set".takeIf { library.hasSetter })
            return Signature(type, declared.joinToString(", "), passed, typeParameters, constraints, accessors.ifEmpty { listOf("get") })
        }
        val receiver = member.owner as? SemanticType.Source
        return when (val element = member.element) {
            is CSharpMethodDeclaration -> {
                val type = writer.source(element.returnType, receiver) ?: return null
                val (declared, passed) = sourceParameters(writer, element.parameterList, receiver, details) ?: return null
                val typeParameters = element.typeParameterList?.parameters.orEmpty().mapNotNull { it.identifier?.text }
                val constraints = if (withConstraints) element.constraintClauses.joinToString("") { " " + CSharpStubsText.collapse(it.text) } else ""
                Signature(type, declared, passed, typeParameters, constraints, emptyList())
            }
            is CSharpPropertyDeclaration -> Signature(writer.source(element.type, receiver) ?: return null, "", emptyList(), emptyList(), "", accessors(element.accessorList, element.expressionBody != null))
            is CSharpIndexerDeclaration -> {
                val (declared, passed) = sourceParameters(writer, element.parameterList, receiver, details) ?: return null
                Signature(writer.source(element.type, receiver) ?: return null, declared, passed, emptyList(), "", accessors(element.accessorList, element.expressionBody != null))
            }
            is CSharpVariableDeclarator -> {
                val declaration = element.parent as? CSharpVariableDeclaration ?: return null
                Signature(writer.source(declaration.type, receiver) ?: return null, "", emptyList(), emptyList(), "", emptyList())
            }
            is CSharpEventDeclaration -> Signature(writer.source(element.type, receiver) ?: return null, "", emptyList(), emptyList(), "", emptyList())
            else -> null
        }
    }

    private fun accessors(list: CSharpAccessorList?, expressionBodied: Boolean): List<String> =
        if (expressionBodied) listOf("get") else list?.accessors.orEmpty().mapNotNull { it.keyword?.text }.filter { it == "get" || it == "set" || it == "init" }.ifEmpty { listOf("get") }

    private fun sourceParameters(writer: CSharpCodeWriter, list: CSharpBaseParameterList?, receiver: SemanticType.Source?, details: Details): Pair<String, List<Pair<String, String>>>? {
        val declared = ArrayList<String>()
        val passed = ArrayList<Pair<String, String>>()
        for (p in list?.parameters.orEmpty()) {
            val modifiers = p.modifiers.map { it.text }.filter { it in PARAMETER_MODIFIERS }
            val type = writer.source(p.type, receiver) ?: return null
            val name = p.identifier?.text ?: return null
            // `[CallerMemberName] string name = ""`, `CancellationToken ct = default`: without them calls of the implementation fail with CS7036
            val attributes = if (details == Details.NONE) "" else p.attributeLists.joinToString("") { CSharpStubsText.collapse(it.text) + " " }
            val default = p.default?.value?.takeIf { details == Details.FULL }?.let { " = " + CSharpStubsText.collapse(it.text) }.orEmpty()
            declared += attributes + (modifiers + listOf(type, name)).joinToString(" ") + default
            passed += (modifiers.firstOrNull { it == "ref" || it == "out" || it == "in" } ?: "") to name
        }
        return declared.joinToString(", ") to passed
    }

    private fun libraryConstraints(writer: CSharpCodeWriter, member: IndexedMember, arguments: List<SemanticType?>, names: List<String>): String? {
        val clauses = member.typeParameters.mapNotNull { parameter ->
            val parts = ArrayList<String>()
            if (parameter.isUnmanaged) parts += "unmanaged" else if (parameter.isStruct) parts += "struct" else if (parameter.isClass) parts += "class"
            for (constraint in parameter.constraints) {
                if (constraint is IndexedTypeRef.Named && constraint.fullName == "System.ValueType" && parameter.isStruct) continue
                parts += writer.ref(constraint, arguments, names) ?: return null
            }
            if (parameter.hasNew) parts += "new()"
            if (parts.isEmpty()) null else " where ${parameter.name} : ${parts.joinToString(", ")}"
        }
        return clauses.joinToString("")
    }

    private companion object {
        val ACCESS = listOf("public", "protected", "internal", "private")
        val PARAMETER_MODIFIERS = setOf("ref", "out", "in", "params", "scoped", "readonly")
        val RECORD_MEMBERS = setOf("Equals", "GetHashCode", "PrintMembers", "EqualityContract")
        /** What every object has: not worth delegating, as Rider leaves them out. */
        val OBJECT_MEMBERS = setOf("Equals", "GetHashCode", "ToString", "GetType", "MemberwiseClone", "Finalize", "ReferenceEquals")
    }
}

/**
 * The edits of a generator (pure on the text, tested without an editor): the members at [CSharpGenerateSite.insertAt] — a blank line
 * between them and after the member before, indented one level inside the type with the code style's unit — the base types after the
 * base list (or a new one after the name), the `using` directives in their place among the others.
 */
object NativeCSharpGenerateEdits {
    /** [steps]: the insertions in the order made, each at an offset of the text as it was then (a document repeats them as they are). */
    class Result(val text: String, val membersRange: TextRange, val steps: List<Pair<Int, String>>)

    fun apply(text: String, site: CSharpGenerateSite, code: CSharpGeneratedCode, unit: String): Result {
        val typeIndent = NativeCSharpUsingEdits.indentOf(text, site.type.textRange.startOffset).orEmpty()
        val memberIndent = typeIndent + unit
        val block = code.members.joinToString("\n\n") { member -> reindent(member, memberIndent, unit) }
        var after = site.insertAt
        while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
        val closesOnLine = after < text.length && text[after] == '}'
        val insertion = if (site.atCaretLine) {
            // at the start of the blank line, which stays after the members; a blank line before them unless the type opens there
            var previous = site.insertAt - 1
            while (previous > 0 && text[previous - 1] != '\n') previous--
            val previousLine = text.substring(maxOf(previous, 0), maxOf(site.insertAt - 1, 0)).trim()
            (if (previousLine.isEmpty() || previousLine.endsWith("{")) "" else "\n") + block + "\n"
        } else (if (site.afterMember) "\n\n" else "\n") + block + when {
            closesOnLine -> "\n" + typeIndent
            // a member or a comment follows: a blank line before it
            !site.afterMember && text.substring(site.insertAt).trimStart().let { it.isNotEmpty() && !it.startsWith("}") } -> "\n"
            else -> ""
        }
        val edits = ArrayList<Pair<Int, String>>()
        edits += site.insertAt to insertion
        if (code.baseTypes.isNotEmpty()) {
            val baseList = site.type.baseList
            val anchor = baseList?.types?.lastOrNull()?.textRange?.endOffset
            if (anchor != null) edits += anchor to code.baseTypes.joinToString("") { ", $it" }
            else {
                val afterName = (site.type.parameterList ?: site.type.typeParameterList ?: site.type.identifier)?.textRange?.endOffset
                if (afterName != null) edits += afterName to " : " + code.baseTypes.joinToString(", ")
            }
        }
        var result = text
        val steps = ArrayList<Pair<Int, String>>()
        var membersStart = site.insertAt
        for ((offset, inserted) in edits.sortedByDescending { it.first }) {
            result = result.substring(0, offset) + inserted + result.substring(offset)
            steps += offset to inserted
            if (offset <= membersStart && offset != site.insertAt) membersStart += inserted.length
        }
        var membersEnd = membersStart + insertion.length
        for (namespace in code.usings) {
            val insertionOfUsing = CSharpUsings.insertion(result, namespace) ?: continue
            if (CSharpUsings.isVisible(namespace, result) || globallyImported(site.file, namespace)) continue
            result = result.substring(0, insertionOfUsing.offset) + insertionOfUsing.text + result.substring(insertionOfUsing.offset)
            steps += insertionOfUsing.offset to insertionOfUsing.text
            if (insertionOfUsing.offset <= membersStart) {
                membersStart += insertionOfUsing.text.length
                membersEnd += insertionOfUsing.text.length
            }
        }
        return Result(result, TextRange(membersStart, membersEnd), steps)
    }

    /** `global using` of [namespace] (implicit usings of the project too): [file] needs no directive of its own. */
    fun globallyImported(file: CSharpFile, namespace: String): Boolean =
        CSharpSemanticEnvironment.globalUsings(file).any { it.alias == null && !it.isStatic && it.namespace.removePrefix("global::") == namespace }

    /** [member] written with 4-space levels, at [indent] with [unit] per level. */
    fun reindent(member: String, indent: String, unit: String): String = member.lines().joinToString("\n") { line ->
        if (line.isBlank()) "" else {
            val spaces = line.takeWhile { it == ' ' }.length
            indent + unit.repeat(spaces / 4) + " ".repeat(spaces % 4) + line.substring(spaces)
        }
    }
}
