package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpStubElementImpl
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import java.util.IdentityHashMap

/**
 * The one syntactic resolver of csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9): Go to Declaration and the usages under the caret (A2,
 * [NativeCSharpNavigation]), the colors of identifiers (A4, [NativeCSharpSemanticColors]) and rename (A5, [NativeCSharpRename]) all ask it.
 * Locals, parameters, local functions, type parameters, labels and range variables come from the scopes of the file ([NativeCSharpScopes],
 * cached per change of the file); a simple name that no scope declares is looked up in the members of the enclosing types (their partial
 * parts and base classes of the solution included), then of the types of `using static`, then among the types of the file and of the
 * solution by name and arity. Other files are read from their stubs ([CSharpStubIndexKeys.TYPE_NAMES], `childrenStubs`, `baseTypes`), never
 * parsed. `this.X` / `base.X` / `Type.X` / `Outer.Inner` / `new T { X = ... }` reach the members of that type.
 *
 * Not resolved (no target, so the language server answers when it is ready): the name after a dot of anything but `this`, `base` and a type
 * of the solution, named arguments, aliases, members of library types, types of referenced assemblies. Overloads are not told apart (step
 * 11d): every member of the name in the nearest type is a target. One instance per use: its caches are of one moment of the stubs.
 */
class NativeCSharpResolver(val file: CSharpFile) {
    private val project = file.project
    val scopes: NativeCSharpScopes = NativeCSharpScopes.of(file)
    private val fileTypesByQualifiedName = LinkedHashMap<String, MutableList<TypePart.Psi>>()
    private val fileTypesByName = HashMap<String, MutableList<TypePart.Psi>>()
    private val qualifiedNames = IdentityHashMap<PsiElement, String>()
    private val types = HashMap<String, TypeInfo?>()
    private val members = HashMap<String, Map<String, Member>>()
    private val stubsByName = HashMap<String, List<TypePart>>()
    private val resolved = HashMap<Pair<String, Int>, TypeInfo?>()
    private val preferredNamespaces = HashSet<String>()
    private val staticUsings = ArrayList<CSharpType>()
    private val virtualFile = file.viewProvider.virtualFile

    // `using static A.B.T;`: the members of `T` by simple name, after those of the enclosing types
    val staticImports: List<TypeInfo> by lazy { staticUsings.mapNotNull(::typeOf) }

    init {
        collectFileTypes()
    }

    // ---- symbols of the scopes

    /**
     * The local symbol [leaf] (an identifier) declares or stands for: a local, parameter, local function, type parameter, label, range
     * variable. Null for anything else, and for a use of a primary constructor's parameter that a member of the same name hides.
     */
    fun symbolAt(leaf: PsiElement): LocalSymbol? {
        val symbol = scopes.symbolAt(leaf) ?: return null
        if (leaf != symbol.declaration && isHiddenByMember(symbol, leaf)) return null
        return symbol
    }

    /** The uses of [symbol] that are its (a member of the type hides a parameter of the primary constructor), the declaration not included. */
    fun references(symbol: LocalSymbol): List<PsiElement> =
        if (symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) symbol.references else symbol.references.filterNot { isHiddenByMember(symbol, it) }

    /** The member of an enclosing type named [name] where [at] stands, when there is one: it hides a parameter of the primary constructor. */
    fun memberHiding(name: String, at: PsiElement): Member? = enclosingMember(at, name)

    private fun isHiddenByMember(symbol: LocalSymbol, leaf: PsiElement): Boolean =
        symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER && enclosingMember(leaf, symbol.name) != null

    /** Read and written ranges of [symbol] in its file, the declaration a write (kinds of [NativeCSharpUsageKinds]). */
    fun usages(symbol: LocalSymbol): Usages {
        val read = ArrayList<TextRange>()
        val write = ArrayList<TextRange>()
        for (leaf in listOf(symbol.declaration) + references(symbol)) when (NativeCSharpUsageKinds.kindOfLeaf(leaf)) {
            CSharpUsageKind.DECLARATION, CSharpUsageKind.WRITE -> write += leaf.textRange
            else -> read += leaf.textRange
        }
        return Usages(read.sortedBy { it.startOffset }, write.sortedBy { it.startOffset })
    }

    class Usages(val read: List<TextRange>, val write: List<TextRange>)

    // ---- declarations of a name

    /**
     * Where Go to Declaration from [leaf] goes: the name leaf of a local symbol; declarations of members and types (several for overloads,
     * partial parts or a type two imported namespaces both have). Null when [leaf] is no name, names a declaration itself, or nothing is found.
     */
    fun declarations(leaf: PsiElement): List<PsiElement>? {
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        scopes.symbolAt(leaf)?.let { symbol ->
            if (leaf == symbol.declaration) return null
            if (!isHiddenByMember(symbol, leaf)) return listOf(symbol.declaration)
        }
        val name = leaf.parent as? CSharpSimpleName ?: return null
        if (name.identifier != leaf) return null
        return targetsOf(name, leaf.text).takeIf { it.isNotEmpty() }
    }

    private fun targetsOf(name: CSharpSimpleName, text: String): List<PsiElement> {
        val arity = arity(name)
        var top: PsiElement = name
        while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
        when (val holder = top.parent) {
            // `using A.B;` names namespaces; `using static A.B.T;`, `using X = A.B.T;` a type on the right
            is CSharpUsingDirective -> if (top == holder.namespaceOrType) {
                if (holder.alias == null && holder.staticKeyword == null || !isRightmost(name, top)) return emptyList()
                return visibleTypes(name, text, arity).flatMap { it.targets() }
            }
            is CSharpBaseNamespaceDeclaration -> if (top == holder.nameElement) return emptyList()
            is CSharpAttribute -> if (top == holder.nameElement) {
                if (name != top) return emptyList()
                return typeTargets(name, text, arity).ifEmpty { typeTargets(name, text + "Attribute", arity) }
            }
        }
        val parent = name.parent
        return when {
            parent is CSharpQualifiedName && name == parent.right -> qualifierType(parent.left)?.let { membersOf(it)["$text`$arity"]?.targets() }.orEmpty()
            parent is CSharpMemberAccessExpression && name == parent.nameElement -> qualifierType(parent.expression)?.let { membersOf(it)[text]?.targets() }.orEmpty()
            parent is CSharpAssignmentExpression && name == parent.left && NativeCSharpScopes.isObjectInitializer(parent.parent) -> {
                val creation = parent.parent?.parent as? CSharpObjectCreationExpression
                creation?.type?.let(::typeOf)?.let { membersOf(it)[text]?.targets() }.orEmpty()
            }
            !NativeCSharpScopes.isFreeName(name) || text in NativeCSharpScopes.CONTEXTUAL -> emptyList()
            isTypeOrNamespace(name) -> typeTargets(name, text, arity)
            else -> {
                val member = if (arity == 0) enclosingMember(name, text) else enclosingMembers(name).firstNotNullOfOrNull { it["$text<$arity>"] ?: it["$text`$arity"] }
                member?.targets() ?: staticImports.firstNotNullOfOrNull { membersOf(it)[text] }?.targets() ?: visibleTypes(name, text, arity).flatMap { it.targets() }
            }
        }
    }

    /** Types only (`Color Color` finds the type): nested types of the enclosing ones, then those of the namespaces the usage sees. */
    private fun typeTargets(name: PsiElement, text: String, arity: Int): List<PsiElement> =
        enclosingMembers(name).firstNotNullOfOrNull { it["$text`$arity"] }?.targets() ?: visibleTypes(name, text, arity).flatMap { it.targets() }

    /**
     * Where only a type or a namespace can stand: a type position ([NativeCSharpTypePositions]), the left side of a qualified name, the
     * constraint of a type parameter.
     */
    private fun isTypeOrNamespace(name: CSharpSimpleName): Boolean =
        NativeCSharpTypePositions.isType(name) || (name.parent as? CSharpQualifiedName)?.left == name

    // ---- the types of this file

    private fun collectFileTypes() {
        for (declaration in scopes.declarations) {
            if (declaration is CSharpBaseNamespaceDeclaration) {
                CSharpDeclarationNames.name(declaration)?.let { ns -> generateSequence(ns) { it.substringBeforeLast('.', "").ifEmpty { null } }.forEach { preferredNamespaces += it } }
                declaration.usings.forEach(::addUsing)
                continue
            }
            if (declaration !is CSharpBaseTypeDeclaration && declaration !is CSharpDelegateDeclaration) continue
            if (declaration.node.elementType == SyntaxKind.ExtensionBlockDeclaration) continue
            val name = CSharpDeclarationNames.nameElement(declaration)?.text ?: continue
            val qualifiedName = qualifiedName(declaration) ?: continue
            val part = TypePart.Psi(declaration as CSharpMemberDeclaration, qualifiedName, name, namespaceOf(declaration))
            fileTypesByQualifiedName.getOrPut(TypePart.key(qualifiedName, part.arity)) { ArrayList() } += part
            fileTypesByName.getOrPut(name) { ArrayList() } += part
        }
        file.compilationUnit?.usings?.forEach(::addUsing)
    }

    private fun addUsing(using: CSharpUsingDirective) {
        if (using.alias == null && using.staticKeyword != null) using.namespaceOrType?.let { staticUsings += it }
        if (using.alias == null && using.staticKeyword == null) using.namespaceOrType?.let { preferredNamespaces += compact(it).removePrefix("global::") }
    }

    /** `Ns.Outer.Inner` of a type declaration of the file, as the stubs spell it; null when a name around is missing. */
    private fun qualifiedName(declaration: PsiElement): String? = qualifiedNames.getOrPut(declaration) { psiQualifiedName(declaration) }

    // ---- types: of the file, of the solution (stubs)

    private fun stubTypes(name: String): List<TypePart> = stubsByName.getOrPut(name) {
        NativeCSharpTypeNames.snapshotOf(file.originalFile.viewProvider.virtualFile)?.let { return@getOrPut it.stubs[name].orEmpty() }
        stubParts(project, virtualFile, name)
    }

    /** Every declaration of a type named [name] (any arity, any namespace, nested ones too) of this file and of the solution's other files. */
    fun typeParts(name: String): List<TypePart> = fileTypesByName[name].orEmpty() + stubTypes(name)

    /** The type a nested-type [member] of [membersOf] stands for. */
    fun nestedTypeOf(member: Member): TypeInfo? = member.nestedType()

    /** All parts of the type [qualifiedName] with [arity]: those of the file, and of other files when it is partial or not of this file. */
    fun typeInfo(qualifiedName: String, arity: Int): TypeInfo? = types.getOrPut(TypePart.key(qualifiedName, arity)) {
        val name = qualifiedName.substringAfterLast('.')
        val own = fileTypesByQualifiedName[TypePart.key(qualifiedName, arity)].orEmpty()
        val parts = ArrayList<TypePart>(own)
        if (own.isEmpty() || own.any { "partial" in it.modifiers }) parts += stubTypes(name).filter { it.qualifiedName == qualifiedName && it.arity == arity }
        if (parts.isEmpty()) null else TypeInfo(qualifiedName, arity, parts)
    }

    /** The type a declaration of this file declares, with all its parts. */
    fun declaredType(declaration: PsiElement): TypeInfo? = qualifiedName(declaration)?.let { typeInfo(it, TypePart.typeParameters(declaration)) }

    /** A type by its simple name, leniently (for colors): of this file first, then of the solution, preferring the namespaces of the file and its usings. */
    fun resolveType(name: String, arity: Int): TypeInfo? = resolved.getOrPut(name to arity) {
        fileTypesByName[name]?.firstOrNull { it.arity == arity }?.let { return@getOrPut typeInfo(it.qualifiedName, arity) }
        if (name in NativeCSharpScopes.CONTEXTUAL) return@getOrPut null
        val candidates = stubTypes(name).filter { it.arity == arity }
        val preferred = candidates.firstOrNull { it.qualifiedName.substringBeforeLast('.', "") in preferredNamespaces } ?: candidates.firstOrNull()
        preferred?.let { typeInfo(it.qualifiedName, arity) }
    }

    /**
     * The types named [name] with [arity] that [at] sees by simple name, strictly (for navigation and rename): those declared at the top of
     * the namespaces around it (the global one included) and of the namespaces the file imports; nested types were found as members.
     */
    fun visibleTypes(at: PsiElement, name: String, arity: Int): List<TypeInfo> {
        if (name in NativeCSharpScopes.CONTEXTUAL) return emptyList()
        val visible = visibleNamespaces(at)
        val parts = fileTypesByName[name].orEmpty() + stubTypes(name)
        return parts.filter { it.arity == arity && it.namespace != null && it.namespace in visible }.map { it.qualifiedName }.distinct().mapNotNull { typeInfo(it, arity) }
    }

    /** `A.B.C` around [at] gives `""`, `A`, `A.B`, `A.B.C`; and the namespaces of the `using` directives of the file and of those namespaces. */
    private fun visibleNamespaces(at: PsiElement): Set<String> {
        val visible = HashSet<String>()
        visible += ""
        val namespaces = generateSequence(at.parent) { if (it is CSharpFile) null else it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>().toList()
        val segments = namespaces.asReversed().flatMap { compact(it.nameElement).split('.') }
        for (i in 1..segments.size) visible += segments.subList(0, i).joinToString(".")
        val usings = file.compilationUnit?.usings.orEmpty() + namespaces.flatMap { it.usings }
        for (using in usings) if (using.alias == null && using.staticKeyword == null) visible += compact(using.namespaceOrType).removePrefix("global::")
        return visible
    }

    /** Members of [type] by simple name: its parts, then its base types of the solution (members of the nearer type win). */
    fun membersOf(type: TypeInfo): Map<String, Member> = members[type.key] ?: run {
        val map = LinkedHashMap<String, Member>()
        collectMembers(type, map, HashSet(), 0)
        members[type.key] = map
        map
    }

    private fun collectMembers(type: TypeInfo, map: MutableMap<String, Member>, visited: MutableSet<String>, depth: Int) {
        if (!visited.add(type.key) || depth > 6) return
        // the parts of one type together: overloads in two parts are all targets
        val own = LinkedHashMap<String, Member>()
        for (part in type.parts) part.members { name, member -> own[name]?.merge(member) ?: own.put(name, member) }
        for ((name, member) in own) map.putIfAbsent(name, member)
        for (part in type.parts) for ((base, arity) in part.bases()) baseOf(part, base, arity)?.let { collectMembers(it, map, visited, depth + 1) }
    }

    /**
     * The base [name] of [part] by its simple name: for a part of another file, first a type of that name in the types and namespaces
     * around the part (as C# looks it up there), not one the usings of this file prefer — a DTO `StressTest` imported here is not the
     * entity's base `StressTest` (E-190); then leniently, as [resolveType].
     */
    private fun baseOf(part: TypePart, name: String, arity: Int): TypeInfo? {
        if (part !is TypePart.Psi || part.declaration.containingFile?.viewProvider?.virtualFile != virtualFile) {
            var scope = part.qualifiedName.substringBeforeLast('.', "")
            while (true) {
                typeInfo(if (scope.isEmpty()) name else "$scope.$name", arity)?.let { return it }
                if (scope.isEmpty()) break
                scope = scope.substringBeforeLast('.', "")
            }
        }
        return resolveType(name, arity)
    }

    /**
     * The types around [element], the innermost first. A name in the base list or an attribute of a type is not looked up in that type's
     * members (`class A : B` does not see a `B` nested in `A`).
     */
    fun enclosingTypes(element: PsiElement): Sequence<TypeInfo> = sequence {
        var child = element
        var current = element.parent
        while (current != null && current !is CSharpFile) {
            if ((current is CSharpBaseTypeDeclaration || current is CSharpDelegateDeclaration) && current.node.elementType != SyntaxKind.ExtensionBlockDeclaration &&
                child !is CSharpBaseList && child !is CSharpAttributeList
            ) {
                qualifiedName(current)?.let { typeInfo(it, TypePart.typeParameters(current)) }?.let { yield(it) }
            }
            child = current
            current = current.parent
        }
    }

    private fun enclosingMembers(element: PsiElement): Sequence<Map<String, Member>> = enclosingTypes(element).map(::membersOf)

    fun enclosingMember(element: PsiElement, name: String): Member? = enclosingMembers(element).firstNotNullOfOrNull { it[name] }

    /** The type [type] (a name as written) stands for, when it is one of the file or the solution. */
    fun typeOf(type: CSharpType): TypeInfo? = when (type) {
        is CSharpSimpleName -> type.identifier?.let { if (scopes.symbolAt(it) != null) null else resolveType(it.text, arity(type)) }
        // `Outer.Inner`, or `Ns.Type` whose namespace part tells nothing here: the type by its simple name then
        is CSharpQualifiedName -> qualifierType(type) ?: type.right?.let(::typeOf)
        is CSharpAliasQualifiedName -> type.nameElement?.let(::typeOf)
        else -> null
    }

    /** The type whose members `qualifier.X` reaches: `this` / `base`, a type of the file or the solution, a nested type of one. */
    fun qualifierType(qualifier: PsiElement?): TypeInfo? = when (qualifier) {
        is CSharpThisExpression, is CSharpBaseExpression -> enclosingTypes(qualifier).firstOrNull()
        is CSharpSimpleName -> {
            val identifier = qualifier.identifier
            if (identifier == null || scopes.symbolAt(identifier) != null) null
            else {
                val member = enclosingMember(qualifier, identifier.text)
                if (member != null) member.nestedType() else resolveType(identifier.text, arity(qualifier))
            }
        }
        is CSharpMemberAccessExpression -> qualifier.nameElement?.identifier?.text?.let { name -> qualifierType(qualifier.expression)?.let { membersOf(it)[name]?.nestedType() } }
        is CSharpQualifiedName -> qualifier.right?.identifier?.text?.let { name -> qualifierType(qualifier.left)?.let { membersOf(it)[name]?.nestedType() } }
        else -> null
    }

    private fun Member.nestedType(): TypeInfo? = nestedType?.let { typeInfo(it, nestedArity) }

    companion object {
        /**
         * The parts of the types named [name] in the files of the solution other than [virtualFile] (null: all): from the stubs; from the PSI
         * where the AST is loaded anyway. [files] collects the files of every declaration of that name, the ones of [virtualFile] included.
         */
        fun stubParts(project: Project, virtualFile: VirtualFile?, name: String, files: MutableCollection<VirtualFile>? = null): List<TypePart> {
            val parts = ArrayList<TypePart>()
            StubIndex.getInstance().processElements(CSharpStubIndexKeys.TYPE_NAMES, name, project, io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(project), CSharpElement::class.java) { element ->
                val file = element.containingFile?.viewProvider?.virtualFile
                if (files != null && file != null) files += file
                if (file != virtualFile && (element is CSharpBaseTypeDeclaration || element is CSharpDelegateDeclaration)) {
                    val stub = (element as? CSharpStubElementImpl)?.greenStub
                    if (stub != null) {
                        val containers = NativeCSharpStubDeclarations.containers(stub)
                        val namespace = if (containers.any { it.second.isType }) null else containers.joinToString(".") { it.first }
                        parts += TypePart.Stub(stub, (containers.map { it.first } + name).joinToString("."), name, namespace)
                    } else if (element is CSharpMemberDeclaration) {
                        // the AST of that file is loaded anyway (it is open): its PSI answers
                        psiQualifiedName(element)?.let { parts += TypePart.Psi(element, it, name, namespaceOf(element)) }
                    }
                }
                true
            }
            return parts
        }

        /** The namespace of a type declared at the top of a namespace (`""` for the global one); null for a nested type. */
        private fun namespaceOf(declaration: PsiElement): String? {
            val names = ArrayList<String>()
            var current: PsiElement? = declaration.parent
            while (current != null && current !is CSharpFile) {
                when (current) {
                    is CSharpBaseNamespaceDeclaration -> names += CSharpDeclarationNames.name(current) ?: return null
                    is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> return null
                }
                current = current.parent
            }
            return names.asReversed().joinToString(".")
        }

        private fun psiQualifiedName(declaration: PsiElement): String? {
            val names = ArrayList<String>()
            var current: PsiElement? = declaration
            while (current != null && current !is CSharpFile) {
                when (current) {
                    is CSharpBaseNamespaceDeclaration -> names += CSharpDeclarationNames.name(current) ?: return null
                    is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration ->
                        if (current.node.elementType != SyntaxKind.ExtensionBlockDeclaration) names += CSharpDeclarationNames.nameElement(current)?.text ?: return null
                }
                current = current.parent
            }
            return names.asReversed().joinToString(".")
        }

        fun arity(name: CSharpSimpleName): Int = (name as? CSharpGenericName)?.typeArgumentList?.arguments?.size ?: 0

        fun compact(element: PsiElement?): String = element?.text?.filterNot(Char::isWhitespace).orEmpty()

        fun isRightmost(name: PsiElement, top: PsiElement): Boolean {
            var current = name
            while (current != top) {
                val parent = current.parent
                val right = when (parent) {
                    is CSharpQualifiedName -> parent.right
                    is CSharpAliasQualifiedName -> parent.nameElement
                    else -> return false
                }
                if (current != right) return false
                current = parent
            }
            return true
        }
    }
}

enum class TypeKind(val key: TextAttributesKey) {
    CLASS(CSharpColors.CLASS), STATIC_CLASS(CSharpColors.STATIC_CLASS), RECORD(CSharpColors.RECORD), STRUCT(CSharpColors.STRUCT),
    RECORD_STRUCT(CSharpColors.RECORD_STRUCT), INTERFACE(CSharpColors.INTERFACE), ENUM(CSharpColors.ENUM), DELEGATE(CSharpColors.DELEGATE);

    companion object {
        fun of(type: IElementType, modifiers: Collection<String>): TypeKind? = when (type) {
            SyntaxKind.ClassDeclaration, SyntaxKind.UnionDeclaration -> if ("static" in modifiers) STATIC_CLASS else CLASS
            SyntaxKind.RecordDeclaration -> RECORD
            SyntaxKind.RecordStructDeclaration -> RECORD_STRUCT
            SyntaxKind.StructDeclaration -> STRUCT
            SyntaxKind.InterfaceDeclaration -> INTERFACE
            SyntaxKind.EnumDeclaration -> ENUM
            SyntaxKind.DelegateDeclaration -> DELEGATE
            else -> null
        }
    }
}

/**
 * A member of a type as a name in its body sees it: the key of its declaration and of a use, a nested type with its qualified name and
 * arity, and where it is declared ([targets]: one per overload or part; a stub's PSI is made only when asked, the AST not loaded).
 */
class Member(val declarationKey: TextAttributesKey, val referenceKey: TextAttributesKey, val nestedType: String? = null, val nestedArity: Int = 0) {
    private val sources = ArrayList<() -> PsiElement?>(1)

    /** The modifiers as written (of the first declaration when merged): who may see the member after a dot (task C3). */
    var modifiers: Collection<String> = emptyList()
        private set

    fun at(source: () -> PsiElement?): Member = apply { sources += source }

    fun withModifiers(modifiers: Collection<String>): Member = apply { this.modifiers = modifiers }

    internal fun merge(other: Member) {
        sources += other.sources
    }

    fun targets(): List<PsiElement> = sources.mapNotNull { it() }.distinct()

    companion object {
        fun method(modifiers: Collection<String>, extension: Boolean): Member = when {
            extension -> Member(CSharpColors.EXTENSION_METHOD_DECLARATION, CSharpColors.EXTENSION_METHOD_CALL)
            "static" in modifiers -> Member(CSharpColors.STATIC_METHOD_DECLARATION, CSharpColors.STATIC_METHOD_CALL)
            else -> Member(CSharpColors.METHOD_DECLARATION, CSharpColors.METHOD_CALL)
        }.withModifiers(modifiers)

        fun field(modifiers: Collection<String>, event: Boolean): Member = same(
            when {
                event -> CSharpColors.EVENT
                "const" in modifiers -> CSharpColors.CONSTANT
                "static" in modifiers -> CSharpColors.STATIC_FIELD
                else -> CSharpColors.FIELD
            },
        ).withModifiers(modifiers)

        fun property(modifiers: Collection<String>): Member = same(if ("static" in modifiers) CSharpColors.STATIC_PROPERTY else CSharpColors.PROPERTY).withModifiers(modifiers)

        fun same(key: TextAttributesKey): Member = Member(key, key)
    }
}

/** One declaration of a type: a part of the file (PSI) or of another file (its stub, so that file is not parsed). [namespace]: null when nested. */
sealed class TypePart(val qualifiedName: String, val name: String, val namespace: String?) {
    abstract val elementType: IElementType
    abstract val modifiers: Collection<String>
    abstract val arity: Int

    /** The declaration, for navigation: PSI made from the stub when it is one. */
    abstract fun element(): PsiElement?

    /**
     * The members declared in this part, nested types included; a positional parameter of a record is a property. A generic method is
     * also under `Name<arity>`, a nested type also under `Name` + backtick + arity (a lookup by name and arity).
     */
    abstract fun members(sink: (String, Member) -> Unit)

    /** The base types as written: simple name and arity. */
    abstract fun bases(): List<Pair<String, Int>>

    class Psi(val declaration: CSharpMemberDeclaration, qualifiedName: String, name: String, namespace: String?) : TypePart(qualifiedName, name, namespace) {
        override val elementType: IElementType get() = declaration.node.elementType
        override val modifiers: Collection<String> get() = declaration.modifiers.map { it.text }
        override val arity: Int get() = typeParameters(declaration)

        override fun element(): PsiElement = declaration

        override fun members(sink: (String, Member) -> Unit) {
            val list = when (declaration) {
                is CSharpTypeDeclaration -> declaration.members
                is CSharpEnumDeclaration -> declaration.members
                else -> emptyList()
            }
            for (member in list) {
                val modifiers = member.modifiers.map { it.text }
                when (member) {
                    is CSharpMethodDeclaration -> member.identifier?.let {
                        sink(it.text, Member.method(modifiers, isExtension(member)).at { member })
                        member.typeParameterList?.parameters?.size?.let { n -> sink("${it.text}<$n>", Member.method(modifiers, isExtension(member)).at { member }) }
                    }
                    is CSharpPropertyDeclaration -> member.identifier?.let { sink(it.text, Member.property(modifiers).at { member }) }
                    is CSharpEventDeclaration -> member.identifier?.let { sink(it.text, Member.same(CSharpColors.EVENT).withModifiers(modifiers).at { member }) }
                    is CSharpBaseFieldDeclaration -> {
                        val variables = member.declaration?.variables.orEmpty()
                        for (variable in variables) variable.identifier?.let {
                            val target: PsiElement = if (variables.size == 1) member else variable
                            sink(it.text, Member.field(modifiers, member is CSharpEventFieldDeclaration).at { target })
                        }
                    }
                    is CSharpEnumMemberDeclaration -> member.identifier?.let { sink(it.text, Member.same(CSharpColors.CONSTANT).at { member }) }
                    is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> {
                        val nestedName = CSharpDeclarationNames.nameElement(member)?.text ?: continue
                        val kind = TypeKind.of(member.node.elementType, modifiers) ?: continue
                        val nestedArity = typeParameters(member)
                        val nested = { Member(kind.key, kind.key, "$qualifiedName.$nestedName", nestedArity).withModifiers(modifiers).at { member } }
                        sink(nestedName, nested())
                        sink("$nestedName`$nestedArity", nested())
                    }
                    else -> {}
                }
            }
            if (declaration is CSharpRecordDeclaration) declaration.parameterList?.parameters?.forEach { p -> p.identifier?.let { id -> sink(id.text, Member.same(CSharpColors.PROPERTY).at { id }) } }
        }

        override fun bases(): List<Pair<String, Int>> = (declaration as? CSharpBaseTypeDeclaration)?.baseList?.types.orEmpty().mapNotNull { simpleName(it.type) }
    }

    class Stub(val stub: CSharpStub, qualifiedName: String, name: String, namespace: String?) : TypePart(qualifiedName, name, namespace) {
        override val elementType: IElementType get() = stub.elementType
        override val modifiers: Collection<String> get() = stub.modifiers
        override val arity: Int get() = stub.arity

        override fun element(): PsiElement? = stub.psi

        override fun members(sink: (String, Member) -> Unit) {
            for (child in stub.childrenStubs) {
                if (child !is CSharpStub) continue
                when (val type = child.elementType) {
                    SyntaxKind.MethodDeclaration -> child.name?.let {
                        sink(it, Member.method(child.modifiers, child.isExtensionMethod).at { child.psi })
                        if (child.arity > 0) sink("$it<${child.arity}>", Member.method(child.modifiers, child.isExtensionMethod).at { child.psi })
                    }
                    SyntaxKind.PropertyDeclaration -> child.name?.let { sink(it, Member.property(child.modifiers).at { child.psi }) }
                    SyntaxKind.EventDeclaration -> child.name?.let { sink(it, Member.same(CSharpColors.EVENT).withModifiers(child.modifiers).at { child.psi }) }
                    SyntaxKind.FieldDeclaration, SyntaxKind.EventFieldDeclaration -> for (declaration in child.childrenStubs) {
                        val variables = declaration.childrenStubs
                        for (variable in variables) (variable as? CSharpStub)?.name?.let {
                            sink(it, Member.field(child.modifiers, type == SyntaxKind.EventFieldDeclaration).at { if (variables.size == 1) child.psi else variable.psi })
                        }
                    }
                    SyntaxKind.EnumMemberDeclaration -> child.name?.let { sink(it, Member.same(CSharpColors.CONSTANT).at { child.psi }) }
                    else -> {
                        val kind = TypeKind.of(type, child.modifiers) ?: continue
                        val nestedName = child.name ?: continue
                        val nested = { Member(kind.key, kind.key, "$qualifiedName.$nestedName", child.arity).withModifiers(child.modifiers).at { child.psi } }
                        sink(nestedName, nested())
                        sink("$nestedName`${child.arity}", nested())
                    }
                }
            }
            if (stub.elementType == SyntaxKind.RecordDeclaration || stub.elementType == SyntaxKind.RecordStructDeclaration) {
                stub.parameters?.let(::parameterNames)?.forEach { name ->
                    // the parameter is in the AST only: loaded when navigated to, not to color
                    sink(name, Member.same(CSharpColors.PROPERTY).at { (stub.psi as? CSharpTypeDeclaration)?.parameterList?.parameters?.firstOrNull { it.identifier?.text == name }?.identifier })
                }
            }
        }

        override fun bases(): List<Pair<String, Int>> = stub.baseTypes.mapNotNull(::simpleName)
    }

    companion object {
        fun key(qualifiedName: String, arity: Int): String = "$qualifiedName`$arity"

        fun typeParameters(declaration: PsiElement): Int = when (declaration) {
            is CSharpTypeDeclaration -> declaration.typeParameterList?.parameters?.size ?: 0
            is CSharpDelegateDeclaration -> declaration.typeParameterList?.parameters?.size ?: 0
            else -> 0
        }

        fun isExtension(method: CSharpMethodDeclaration): Boolean =
            method.parameterList?.parameters?.firstOrNull()?.modifiers?.any { it.node.elementType == SyntaxKind.ThisKeyword } == true

        /** `Base`, `Ns.Base<T>`, `global::Base` as a simple name and arity. */
        fun simpleName(type: CSharpType?): Pair<String, Int>? = when (type) {
            is CSharpGenericName -> type.identifier?.text?.let { it to (type.typeArgumentList?.arguments?.size ?: 0) }
            is CSharpSimpleName -> type.identifier?.text?.let { it to 0 }
            is CSharpQualifiedName -> simpleName(type.right)
            is CSharpAliasQualifiedName -> simpleName(type.nameElement)
            else -> null
        }

        /** The same of a base type as a stub keeps it (the text, whitespace collapsed). */
        fun simpleName(text: String): Pair<String, Int>? {
            val open = text.indexOf('<')
            val head = (if (open < 0) text else text.substring(0, open)).substringAfterLast('.').substringAfterLast(':').trim()
            if (head.isEmpty()) return null
            if (open < 0) return head to 0
            var depth = 0
            var arity = 1
            for (c in text.substring(open)) when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ',' -> if (depth == 1) arity++
            }
            return head to arity
        }

        /** The names of a parameter list as a stub keeps it: `(int X, string Y = "")` → `X`, `Y`. */
        fun parameterNames(list: String): List<String> {
            val inner = list.trim().removePrefix("(").removeSuffix(")")
            val names = ArrayList<String>()
            var depth = 0
            val current = StringBuilder()
            fun flush() {
                val head = current.toString().substringBefore('=').trim()
                NAME_AT_END.find(head)?.let { names += it.groupValues[1].removePrefix("@") }
                current.clear()
            }
            for (c in inner) {
                when (c) {
                    '<', '(', '[' -> depth++
                    '>', ')', ']' -> depth--
                }
                if (c == ',' && depth == 0) flush() else current.append(c)
            }
            flush()
            return names
        }

        private val NAME_AT_END = Regex("""([\p{L}_@][\p{L}\p{N}_]*)$""")
    }
}

/** A type seen from the file being resolved: all its parts, the kind (static on any part makes a static class). */
class TypeInfo(val qualifiedName: String, val arity: Int, val parts: List<TypePart>) {
    val key: String get() = TypePart.key(qualifiedName, arity)

    val kind: TypeKind? = parts.firstNotNullOfOrNull { TypeKind.of(it.elementType, it.modifiers) }?.let { kind ->
        if (kind == TypeKind.CLASS && parts.any { "static" in it.modifiers }) TypeKind.STATIC_CLASS else kind
    }

    fun targets(): List<PsiElement> = parts.mapNotNull { it.element() }
}
