package io.github.dotnetsupport.index

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.dotnetsupport.lang.CSharpIcons
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.lsp.RoslynOptions
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.Icon

/**
 * The names of an index for Go to Class / Go to Symbol, read from its tables without making an object per row: the rows of the types by
 * their own name (`Enumerator` of `Dictionary`2+Enumerator`) and of the members by theirs (constructors and operators have no name to
 * type), each sorted, so that a name is a binary search. Made the first time an index is asked, kept while it is open.
 */
internal class AssemblyIndexNames private constructor(val typeNames: Array<String>, private val typeRows: IntArray, val memberNames: Array<String>, private val memberRows: IntArray) {
    fun typeRows(name: String): IntArray = rows(typeNames, typeRows, name)
    fun memberRows(name: String): IntArray = rows(memberNames, memberRows, name)

    private fun rows(names: Array<String>, rows: IntArray, name: String): IntArray {
        var low = 0
        var high = names.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (names[middle] < name) low = middle + 1 else high = middle
        }
        var end = low
        while (end < names.size && names[end] == name) end++
        return rows.copyOfRange(low, end)
    }

    companion object {
        private val cache: MutableMap<AssemblyIndex, AssemblyIndexNames> = Collections.synchronizedMap(WeakHashMap())

        fun of(index: AssemblyIndex): AssemblyIndexNames = cache[index] ?: build(index).also { cache[index] = it }

        private fun build(index: AssemblyIndex): AssemblyIndexNames {
            val typeNames = Array(index.typeCount) { row -> ownName(index.string(index.typeInt(row, 1))) }
            val types = sorted(typeNames, (0 until index.typeCount).toList())
            val memberNames = arrayOfNulls<String>(index.memberCount)
            val members = ArrayList<Int>()
            for (row in 0 until index.memberCount) {
                val kind = index.memberInt(row, 2) and 0xFF
                if (kind == IndexedMemberKind.CONSTRUCTOR.code || kind == IndexedMemberKind.OPERATOR.code) continue
                memberNames[row] = index.string(index.memberInt(row, 0))
                members += row
            }
            @Suppress("UNCHECKED_CAST")
            val sortedMembers = sorted(memberNames as Array<String>, members)
            return AssemblyIndexNames(Array(types.size) { typeNames[types[it]] }, types, Array(sortedMembers.size) { memberNames[sortedMembers[it]] }, sortedMembers)
        }

        private fun sorted(names: Array<String>, rows: List<Int>): IntArray = rows.sortedWith { a, b -> names[a].compareTo(names[b]) }.toIntArray()

        /** `Dictionary`2+Enumerator` -> `Enumerator`. */
        fun ownName(path: String): String = path.substringAfterLast('+').substringBefore('`')
    }
}

/**
 * Go to Class / Go to Symbol over the referenced assemblies of the solution (what the indexer has indexed for its projects): the types —
 * and for symbols their members — of the libraries, as the platform offers the classes of libraries: only when non-project items are
 * included (the checkbox of Go to Class, "All Places" of Search Everywhere), so `scope.isSearchInLibraries`. An assembly ten projects refer
 * to is one index; a type of two indexes of the same assembly and version (the same pack in two folders) is one item. The items are made
 * from the index when they are asked for by name and open the metadata view of the type ([AssemblyNavigation]).
 */
abstract class AssemblyGotoContributor(private val members: Boolean) : ChooseByNameContributorEx, DumbAware {
    // `symbol_search.dotnet_search_reference_assemblies` of the server's page: off, the native side does not search the libraries either
    private val enabled: Boolean get() = RoslynOptions.isOn("symbol_search.dotnet_search_reference_assemblies")

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        if (!scope.isSearchInLibraries || !enabled) return
        val project = scope.project ?: return
        for (name in names(AssemblyIndexService.getInstance(project).allIndexes())) if (!processor.process(name)) return
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        if (!parameters.isSearchInLibraries || !enabled) return
        val project = parameters.project
        val seen = HashSet<String>()
        val indexes = AssemblyIndexService.getInstance(project).allIndexes()
        for (index in indexes) {
            val names = AssemblyIndexNames.of(index)
            for (row in names.typeRows(name)) {
                val type = index.type(row)
                if (seen.add(key(index, type.docId)) && !processor.process(AssemblyTypeItem(project, type))) return
            }
            if (!members) continue
            for (row in names.memberRows(name)) {
                val member = index.member(row)
                if (seen.add(key(index, member.docId)) && !processor.process(AssemblyMemberItem(project, member))) return
            }
        }
    }

    private fun key(index: AssemblyIndex, docId: String): String = index.assemblyName + "," + index.assemblyVersion + "," + docId

    /** The names of all the indexes, each once; kept for the same list of indexes (a keystroke asks again). */
    private fun names(indexes: List<AssemblyIndex>): Array<String> {
        cached?.let { (list, names) -> if (list.size == indexes.size && list.indices.all { list[it] === indexes[it] }) return names }
        val all = LinkedHashSet<String>()
        for (index in indexes) {
            val names = AssemblyIndexNames.of(index)
            all.addAll(names.typeNames)
            if (members) all.addAll(names.memberNames)
        }
        val names = all.toTypedArray()
        cached = indexes to names
        return names
    }

    @Volatile
    private var cached: Pair<List<AssemblyIndex>, Array<String>>? = null
}

/** Go to Class (Ctrl+N) over the types of the referenced assemblies; `Generic.List` finds `System.Collections.Generic.List` too. */
class AssemblyGotoClassContributor : AssemblyGotoContributor(members = false), GotoClassContributor {
    override fun getQualifiedName(item: NavigationItem): String? = (item as? AssemblyTypeItem)?.type?.qualifiedName
    override fun getQualifiedNameSeparator(): String = "."
}

/** Go to Symbol (Ctrl+Alt+Shift+N) over the types and members of the referenced assemblies. */
class AssemblyGotoSymbolContributor : AssemblyGotoContributor(members = true)

/** How an item of an assembly shows: its icon by kind, as the declarations of the solution have them. */
internal object AssemblyItems {
    fun kind(type: IndexedType): DeclarationKind = when (type.kind) {
        IndexedTypeKind.CLASS, IndexedTypeKind.STATIC_CLASS -> if (type.isRecord) DeclarationKind.RECORD else DeclarationKind.CLASS
        IndexedTypeKind.STRUCT -> DeclarationKind.STRUCT
        IndexedTypeKind.INTERFACE -> DeclarationKind.INTERFACE
        IndexedTypeKind.ENUM -> DeclarationKind.ENUM
        IndexedTypeKind.DELEGATE -> DeclarationKind.DELEGATE
    }

    fun kind(member: IndexedMember): DeclarationKind = when (member.kind) {
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD -> DeclarationKind.METHOD
        IndexedMemberKind.PROPERTY -> DeclarationKind.PROPERTY
        IndexedMemberKind.INDEXER -> DeclarationKind.INDEXER
        IndexedMemberKind.FIELD, IndexedMemberKind.CONSTANT -> DeclarationKind.FIELD
        IndexedMemberKind.ENUM_MEMBER -> DeclarationKind.ENUM_MEMBER
        IndexedMemberKind.CONSTRUCTOR -> DeclarationKind.CONSTRUCTOR
        IndexedMemberKind.EVENT -> DeclarationKind.EVENT
        IndexedMemberKind.OPERATOR -> DeclarationKind.OPERATOR
    }

    fun modifiers(isProtected: Boolean, extra: String? = null): Set<String> = setOfNotNull(if (isProtected) "protected" else "public", extra)

    /** `List<T>`, `Dictionary<TKey, TValue>.Enumerator`. */
    fun title(type: IndexedType): String {
        val own = type.typeParameters.takeLast(type.ownArity)
        val outer = type.declaringType?.let { title(it) + "." } ?: ""
        return outer + type.simpleName + if (own.isEmpty()) "" else own.joinToString(", ", "<", ">") { it.name }
    }

    /** `System.Collections 10.0`. */
    fun assembly(index: AssemblyIndex): String = index.assemblyName + " " + AssemblyMetadataText.shortVersion(index.assemblyVersion)
}

/** A type of an assembly in Go to Class: `List<T> (System.Collections.Generic, System.Collections 10.0)`. */
class AssemblyTypeItem(private val project: Project, val type: IndexedType) : NavigationItem, ItemPresentation {
    override fun getName(): String = type.simpleName
    override fun getPresentation(): ItemPresentation = this
    override fun getPresentableText(): String = AssemblyItems.title(type)
    override fun getLocationString(): String = "(" + listOf(type.namespace, AssemblyItems.assembly(type.index)).filter { it.isNotEmpty() }.joinToString(", ") + ")"
    override fun getIcon(unused: Boolean): Icon = CSharpIcons.of(AssemblyItems.kind(type), AssemblyItems.modifiers(type.isProtected, "abstract".takeIf { type.isAbstract && type.kind == IndexedTypeKind.CLASS }),
        type.declaringType?.let { AssemblyItems.kind(it) })
    override fun navigate(requestFocus: Boolean) = AssemblyNavigation.navigate(project, type, null, requestFocus)
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun equals(other: Any?): Boolean = other is AssemblyTypeItem && other.type == type
    override fun hashCode(): Int = type.hashCode()
    override fun toString(): String = "$presentableText $locationString"
}

/** A member of an assembly in Go to Symbol: `Add(T) (List<T>, System.Collections.Generic, System.Collections 10.0)`. */
class AssemblyMemberItem(private val project: Project, val member: IndexedMember) : NavigationItem, ItemPresentation {
    override fun getName(): String = member.name
    override fun getPresentation(): ItemPresentation = this
    override fun getPresentableText(): String = member.name + when (member.kind) {
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD -> member.parameters.joinToString(", ", "(", ")") { member.display(it.typeRef) }
        else -> ""
    }
    override fun getLocationString(): String =
        "(" + listOf(AssemblyItems.title(member.type), member.type.namespace, AssemblyItems.assembly(member.type.index)).filter { it.isNotEmpty() }.joinToString(", ") + ")"
    override fun getIcon(unused: Boolean): Icon = CSharpIcons.of(AssemblyItems.kind(member),
        AssemblyItems.modifiers(member.isProtected, if (member.kind == IndexedMemberKind.CONSTANT) "const" else "abstract".takeIf { member.isAbstract }), AssemblyItems.kind(member.type))
    override fun navigate(requestFocus: Boolean) = AssemblyNavigation.navigate(project, member.type, member, requestFocus)
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun equals(other: Any?): Boolean = other is AssemblyMemberItem && other.member == member
    override fun hashCode(): Int = member.hashCode()
    override fun toString(): String = "$presentableText $locationString"
}
