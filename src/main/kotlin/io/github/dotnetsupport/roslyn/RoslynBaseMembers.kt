package io.github.dotnetsupport.roslyn

import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpFileStructure
import io.github.dotnetsupport.lang.DeclarationKind

/**
 * Go to Base of a member (Ctrl+U on a method, property, event or indexer), as in Rider. The server has no "base member" request: the
 * supertypes come from its type hierarchy, the members of each supertype from the plugin's scanner run on the file of that type. Pure
 * functions here, the requests are in [RoslynGotoSuperHandler].
 */
object RoslynBaseMembers {
    val MEMBER_KINDS = setOf(DeclarationKind.METHOD, DeclarationKind.PROPERTY, DeclarationKind.INDEXER, DeclarationKind.EVENT)

    /** A member under the caret and the type that declares it. */
    class Caret(val type: CSharpDeclarationInfo, val member: CSharpDeclarationInfo, val inBody: Boolean)

    /** The member around [offset], when it is directly in a type: a local function or a lambda are not seen by the scanner anyway. */
    fun memberAt(structure: CSharpFileStructure, offset: Int): Caret? {
        val path = structure.pathTo(offset)
        val member = path.lastOrNull()?.takeIf { it.kind in MEMBER_KINDS } ?: return null
        val type = path.getOrNull(path.size - 2)?.takeIf { it.kind.isType } ?: return null
        return Caret(type, member, member.body?.let { offset > it.startOffset && offset < it.endOffset } == true)
    }

    /**
     * The type of [name] declared in a scanned file: the one at [nameOffset] (where the server put it), else the only one of that name.
     * `List<T>` of the server is `List` of the scanner.
     */
    fun typeIn(structure: CSharpFileStructure, name: String, nameOffset: Int?): CSharpDeclarationInfo? {
        val types = structure.all().filter { it.kind.isType }.toList()
        types.firstOrNull { it.nameRange.startOffset == nameOffset }?.let { return it }
        val simple = name.substringBefore('<').substringAfterLast('.').trim()
        return types.filter { it.name == simple }.singleOrNull()
    }

    /** Members of [candidates] that [member] can override, implement or hide: same kind and name; overloads are told apart by the number of parameters. */
    fun matches(member: CSharpDeclarationInfo, candidates: List<CSharpDeclarationInfo>): List<CSharpDeclarationInfo> {
        val named = candidates.filter { it.kind == member.kind && it.name == member.name }
        if (named.size < 2) return named
        val count = parameterCount(member.parameters)
        return named.filter { parameterCount(it.parameters) == count }.ifEmpty { named }
    }

    /** `(int a, Dictionary<string, int> b = null, string c = ",")` → 3, `()` → 0, `[int i]` of an indexer → 1. */
    fun parameterCount(parameters: String?): Int {
        val inside = parameters?.trim()?.removeSurrounding("(", ")")?.removeSurrounding("[", "]")?.trim().orEmpty()
        if (inside.isEmpty()) return 0
        var depth = 0
        var count = 1
        var quote: Char? = null
        var i = 0
        while (i < inside.length) {
            val c = inside[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' || c == '<' || c == '{' -> depth++
                c == ')' || c == ']' || c == '>' || c == '}' -> depth = maxOf(0, depth - 1)
                c == ',' && depth == 0 -> count++
            }
            i++
        }
        return count
    }

    /** A supertype at [distance] steps up (1 — a direct base); [found] are its members that match, `null` when its source could not be read. */
    class Base<T>(val type: T, val distance: Int, val isInterface: Boolean, val found: List<CSharpDeclarationInfo>?)

    /** Where Ctrl+U goes: [members] (a supertype and its member), or, when no member was found, [types] whose members could not be looked at. */
    class Choice<T>(val members: List<Pair<T, CSharpDeclarationInfo>>, val types: List<T>)

    /**
     * The base symbols of [member]: of the base classes only the nearest one that declares it (an override overrides that one), the
     * members of all interfaces after it; an `override` goes to its base class member alone. Nothing found — the supertypes that were not
     * looked into (metadata), the nearest first, so that Ctrl+U at least gets to the type.
     */
    fun <T> choose(member: CSharpDeclarationInfo, bases: List<Base<T>>): Choice<T> {
        val sorted = bases.sortedBy { it.distance }
        fun hits(base: Base<T>) = base.found.orEmpty().map { base.type to it }
        val nearestClass = sorted.firstOrNull { !it.isInterface && !it.found.isNullOrEmpty() }?.let(::hits).orEmpty()
        val interfaces = sorted.filter { it.isInterface }.flatMap(::hits)
        val members = if ("override" in member.modifiers && nearestClass.isNotEmpty()) nearestClass else nearestClass + interfaces
        return Choice(members, if (members.isEmpty()) sorted.filter { it.found == null }.map { it.type } else emptyList())
    }
}
