package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbol
import io.github.dotnetsupport.lang.Member
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.msbuild.GlobalUsing
import java.util.IdentityHashMap

/**
 * What a name of C# stands for (layer 11a of CSHARP_PSI_MIGRATION.md): a namespace, a type or a member of the solution (its declaration)
 * or of a referenced assembly (the index of the assembly, identified by the documentation comment id Roslyn gives it), a local symbol of
 * the scopes of the file.
 */
sealed class CSharpSymbol {
    /** Where it is declared in the sources: the declarations (a field by its declaration, or declarator when it has several). */
    open val declarations: List<PsiElement> get() = emptyList()

    /** Roslyn's documentation comment id for what has no declaration in the sources (`N:System`, `T:System.Console`, `M:...~ReturnType`). */
    open val id: String? get() = null

    /** The color of a use of it, as the language server's semantic tokens give it; null for what the scopes color. */
    open val referenceKey: TextAttributesKey? get() = null

    class Namespace(val qualifiedName: String) : CSharpSymbol() {
        override val id: String get() = "N:$qualifiedName"
        override val referenceKey: TextAttributesKey get() = CSharpColors.NAMESPACE
        override fun equals(other: Any?): Boolean = other is Namespace && other.qualifiedName == qualifiedName
        override fun hashCode(): Int = qualifiedName.hashCode()
        override fun toString(): String = "namespace $qualifiedName"
    }

    class SourceType(val info: TypeInfo) : CSharpSymbol() {
        override val declarations: List<PsiElement> get() = info.targets()
        override val referenceKey: TextAttributesKey? get() = info.kind?.key
        override fun equals(other: Any?): Boolean = other is SourceType && other.info.key == info.key
        override fun hashCode(): Int = info.key.hashCode()
        override fun toString(): String = "type ${info.qualifiedName}"
    }

    class LibraryType(val type: IndexedType) : CSharpSymbol() {
        override val id: String get() = type.docId
        override val referenceKey: TextAttributesKey get() = keyOf(type)
        override fun equals(other: Any?): Boolean = other is LibraryType && other.type == type
        override fun hashCode(): Int = type.hashCode()
        override fun toString(): String = "type $type"
    }

    /** A local, parameter, local function, type parameter, label or range variable of the scopes of a file. */
    class Local(val symbol: LocalSymbol) : CSharpSymbol() {
        override val declarations: List<PsiElement> get() = listOf(symbol.declaration)
        override fun toString(): String = "${symbol.kind} ${symbol.name}"
    }

    /**
     * A member of a type of the solution: one declaration (one overload). [member] says its colors; [owner] is the type it was found on
     * (the type arguments of its signature come from it).
     */
    class SourceMember(val element: PsiElement, val member: Member, val owner: SemanticType?) : CSharpSymbol() {
        override val declarations: List<PsiElement> get() = listOf(element)
        override val referenceKey: TextAttributesKey get() = member.referenceKey
        override fun equals(other: Any?): Boolean = other is SourceMember && other.element == element
        override fun hashCode(): Int = element.hashCode()
        override fun toString(): String = "member ${element.text.take(40)}"
    }

    /** A member of an assembly; [declaringArguments]: what the type parameters of its type stand for where it is used. */
    class LibraryMember(val member: IndexedMember, val declaringArguments: List<SemanticType?> = emptyList()) : CSharpSymbol() {
        override val id: String get() = docId(member)
        override val referenceKey: TextAttributesKey get() = keyOf(member)
        override fun equals(other: Any?): Boolean = other is LibraryMember && other.member == member
        override fun hashCode(): Int = member.hashCode()
        override fun toString(): String = "member $member"
    }

    companion object {
        /** Roslyn's declaration id of a method names what it returns too (`M:System.Diagnostics.Stopwatch.StartNew~System.Diagnostics.Stopwatch`). */
        fun docId(member: IndexedMember): String {
            val id = member.docId
            val method = member.kind == IndexedMemberKind.METHOD || member.kind == IndexedMemberKind.EXTENSION_METHOD || member.kind == IndexedMemberKind.OPERATOR
            if (!method || '~' in id) return id
            val returns = member.typeRef
            if (returns is IndexedTypeRef.Named && returns.fullName == "System.Void") return id
            // a `ref` return is written without `@`, as Roslyn's DocumentationCommentId does
            return id + "~" + returns.docId().removeSuffix("@")
        }

        fun keyOf(type: IndexedType): TextAttributesKey = when (type.kind) {
            IndexedTypeKind.CLASS -> if (type.isRecord) CSharpColors.RECORD else if (type.isStatic) CSharpColors.STATIC_CLASS else CSharpColors.CLASS
            IndexedTypeKind.STATIC_CLASS -> CSharpColors.STATIC_CLASS
            IndexedTypeKind.STRUCT -> CSharpColors.STRUCT
            IndexedTypeKind.INTERFACE -> CSharpColors.INTERFACE
            IndexedTypeKind.ENUM -> CSharpColors.ENUM
            IndexedTypeKind.DELEGATE -> CSharpColors.DELEGATE
        }

        fun keyOf(member: IndexedMember): TextAttributesKey = when (member.kind) {
            IndexedMemberKind.EXTENSION_METHOD -> CSharpColors.EXTENSION_METHOD_CALL
            IndexedMemberKind.METHOD, IndexedMemberKind.OPERATOR, IndexedMemberKind.CONSTRUCTOR -> if (member.isStatic) CSharpColors.STATIC_METHOD_CALL else CSharpColors.METHOD_CALL
            IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> if (member.isStatic) CSharpColors.STATIC_PROPERTY else CSharpColors.PROPERTY
            IndexedMemberKind.FIELD -> if (member.isStatic) CSharpColors.STATIC_FIELD else CSharpColors.FIELD
            IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> CSharpColors.CONSTANT
            IndexedMemberKind.EVENT -> CSharpColors.EVENT
        }
    }
}

/** The answer for a name: one symbol, or the candidates when the resolver cannot pick one (overloads, two imported types of a name). */
class CSharpResolution(val symbols: List<CSharpSymbol>) {
    val single: CSharpSymbol? get() = symbols.singleOrNull()

    /** The color all the candidates share (overloads of a static method: `Console.WriteLine(x)` with `x` of no known type), else none. */
    val referenceKey: TextAttributesKey? get() = symbols.map { it.referenceKey ?: return null }.distinct().singleOrNull()
    override fun toString(): String = symbols.toString()
}

/**
 * The type of an expression or of a declaration, as far as layer 11a needs it to reach members (`a.B`): a type of the solution or of an
 * assembly with its type arguments, an array, a type parameter. Unknown arguments are null.
 */
sealed class SemanticType {
    abstract val name: String

    /** [outer]: the type around a type nested in a generic one, with its arguments (`Outer<int>.Inner`). */
    class Source(val info: TypeInfo, val arguments: List<SemanticType?>, val outer: Source? = null) : SemanticType() {
        override val name: String get() = info.qualifiedName.substringAfterLast('.')
        override fun toString(): String = info.qualifiedName + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")
    }

    /** [tupleNames]: the element names of a `ValueTuple` written as `(int a, string b)` (null where an element has none). */
    class Library(val type: IndexedType, val arguments: List<SemanticType?>, val tupleNames: List<String?>? = null) : SemanticType() {
        override val name: String get() = type.simpleName
        val fullName: String get() = type.fullName
        override fun toString(): String = type.qualifiedName + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")
    }

    class ArrayOf(val element: SemanticType?, val rank: Int = 1) : SemanticType() {
        override val name: String get() = "Array"
        override fun toString(): String = "$element[]"
    }

    /** A type parameter: of a type ([ofMethod] false) or a method, the [index]-th of [owner]'s list. */
    class Parameter(override val name: String, val owner: PsiElement?, val index: Int, val ofMethod: Boolean) : SemanticType() {
        override fun toString(): String = name
    }

    /** How Roslyn displays the type (`System.Collections.Generic.List<int>`, `int?`, `(int a, string b)`); null when a part is unknown. */
    val display: String? get() = CSharpTypeDisplay.display(this)
}

/**
 * One question's worth of resolvers (a pass of colors, a Go to Declaration, a file of the gate): a resolver per file, the global usings and
 * the namespaces per compilation — computed once, read by every file the question reaches (the declaration of a member in another file is
 * typed by that file's resolver). Not shared between threads, like [io.github.dotnetsupport.lang.NativeCSharpResolver].
 */
class CSharpSemanticSession @JvmOverloads constructor(val project: Project, private val loadsOtherFiles: Boolean = true) {
    private val resolvers = HashMap<CSharpFile, CSharpNameResolver>()
    private val sourceNamespaces = HashMap<String, Boolean>()
    private val libraryNamespaces = IdentityHashMap<AssemblyIndexSet, Set<String>>()
    private val globalUsings = HashMap<String, List<GlobalUsing>>()

    fun resolver(file: CSharpFile): CSharpNameResolver = resolvers.getOrPut(file) { CSharpNameResolver(file, this) }

    /**
     * The resolver of a file a declaration is in, to type that declaration; null when that would load the AST of a file nobody has open and
     * the session must not ([loadsOtherFiles] false: a pass of colors, which reads other files through stubs only).
     */
    fun reachable(file: CSharpFile): CSharpNameResolver? =
        if (loadsOtherFiles || file in resolvers || (file as? com.intellij.psi.impl.source.PsiFileImpl)?.treeElement != null) resolver(file) else null

    internal fun globalUsings(file: CSharpFile): List<GlobalUsing> =
        globalUsings.getOrPut(CSharpSemanticEnvironment.projectOf(file)?.path.orEmpty()) { CSharpSemanticEnvironment.globalUsings(file) }

    /** A namespace that some type of the solution or of [assemblies] is in, or that a namespace of the solution is declared as. */
    fun namespaceExists(name: String, assemblies: AssemblyIndexSet): Boolean {
        if (name.isEmpty()) return true
        val library = libraryNamespaces.getOrPut(assemblies) {
            val all = HashSet<String>()
            for (namespace in assemblies.namespaces) {
                var at = namespace.length
                var prefix = namespace
                while (prefix.isNotEmpty() && all.add(prefix)) {
                    at = prefix.lastIndexOf('.')
                    prefix = if (at < 0) "" else prefix.substring(0, at)
                }
            }
            all
        }
        if (name in library) return true
        return sourceNamespaces.getOrPut(name) {
            var found = false
            StubIndex.getInstance().processElements(CSharpStubIndexKeys.NAMESPACES, name, project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) {
                found = true
                false
            }
            found
        }
    }

    private val sourceExtensions = HashMap<String, List<CSharpElement>>()

    /** The extension methods of the solution named [name] (stub index), remembered for the session. */
    fun sourceExtensions(name: String): List<CSharpElement> = sourceExtensions.getOrPut(name) {
        val found = ArrayList<CSharpElement>()
        StubIndex.getInstance().processElements(CSharpStubIndexKeys.EXTENSION_METHODS, name, project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) {
            found += it
            true
        }
        found
    }

    private val foundTypes = HashMap<Pair<AssemblyIndexSet, String>, java.util.Optional<IndexedType>>()

    /** [AssemblyIndexSet.findType] of [namespace] (null: [name] is a full name) remembered for the session: the same names come again and again. */
    fun findType(assemblies: AssemblyIndexSet, namespace: String?, name: String): IndexedType? =
        foundTypes.getOrPut(assemblies to (namespace?.let { "$it\u0000" }.orEmpty() + name)) {
            java.util.Optional.ofNullable(if (namespace == null) assemblies.findType(name) else assemblies.findType(namespace, name))
        }.orElse(null)

    /**
     * The members of a type of the references with the inherited ones ([AssemblyIndexSet.members]) by name. Kept per reference set for as
     * long as the set lives (the index service keeps one per project until the references change): the work of hiding and substitution is
     * the larger part of resolving a member of a library type, and its answer never changes for one set.
     */
    fun libraryMembers(assemblies: AssemblyIndexSet, type: IndexedType): Map<String, List<AssemblyIndexSet.Inherited>> {
        val perSet = synchronized(LIBRARY_MEMBERS) { LIBRARY_MEMBERS.getOrPut(assemblies) { java.util.concurrent.ConcurrentHashMap() } }
        return perSet.getOrPut(type) { assemblies.members(type).groupBy { it.member.name } }
    }

    private val parameters = HashMap<IndexedMember, List<io.github.dotnetsupport.index.IndexedParameter>>()

    /** [IndexedMember.parameters] remembered for the session: reading them makes their display strings, and overloads are asked again and again. */
    fun parameters(member: IndexedMember): List<io.github.dotnetsupport.index.IndexedParameter> = parameters.getOrPut(member) { member.parameters }

    /** [AssemblyIndexSet.interfaces] kept per reference set, as [libraryMembers]: conversions and inference walk them for every argument. */
    fun interfaces(assemblies: AssemblyIndexSet, type: IndexedType): List<AssemblyIndexSet.Supertype> {
        val perSet = synchronized(INTERFACES) { INTERFACES.getOrPut(assemblies) { java.util.concurrent.ConcurrentHashMap() } }
        return perSet.getOrPut(type) { assemblies.interfaces(type) }
    }

    /** [AssemblyIndexSet.baseTypes] kept per reference set. */
    fun baseTypes(assemblies: AssemblyIndexSet, type: IndexedType): List<AssemblyIndexSet.Supertype> {
        val perSet = synchronized(BASE_TYPES) { BASE_TYPES.getOrPut(assemblies) { java.util.concurrent.ConcurrentHashMap() } }
        return perSet.getOrPut(type) { assemblies.baseTypes(type) }
    }

    /** [AssemblyIndexSet.extensions] kept per reference set: every `.Where(...)` asks for the same keys. */
    fun extensions(assemblies: AssemblyIndexSet, key: String): List<IndexedMember> {
        val perSet = synchronized(EXTENSIONS) { EXTENSIONS.getOrPut(assemblies) { java.util.concurrent.ConcurrentHashMap() } }
        return perSet.getOrPut(key) { assemblies.extensions(key) }
    }

    private companion object {
        val EXTENSIONS = java.util.WeakHashMap<AssemblyIndexSet, java.util.concurrent.ConcurrentHashMap<String, List<IndexedMember>>>()
        val INTERFACES =java.util.WeakHashMap<AssemblyIndexSet, java.util.concurrent.ConcurrentHashMap<IndexedType, List<AssemblyIndexSet.Supertype>>>()
        val BASE_TYPES = java.util.WeakHashMap<AssemblyIndexSet, java.util.concurrent.ConcurrentHashMap<IndexedType, List<AssemblyIndexSet.Supertype>>>()
        val LIBRARY_MEMBERS =java.util.WeakHashMap<AssemblyIndexSet, java.util.concurrent.ConcurrentHashMap<IndexedType, Map<String, List<AssemblyIndexSet.Inherited>>>>()
    }
}
