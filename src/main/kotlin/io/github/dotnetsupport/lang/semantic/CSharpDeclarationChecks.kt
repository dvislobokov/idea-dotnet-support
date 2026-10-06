package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbol
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypePart
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.CompilationOptions
import io.github.dotnetsupport.msbuild.EvaluatedFiles
import io.github.dotnetsupport.msbuild.MsBuildGlob
import io.github.dotnetsupport.msbuild.ProjectContent
import io.github.dotnetsupport.solution.SolutionService

/**
 * Duplicate declarations and declaration order, as Roslyn reports them (each verdict checked with `dotnet build`):
 * - locals: CS0128 (a local or local function declared twice in one scope), CS0136 (a name of an enclosing scope of the same function),
 *   CS1930 / CS1931 (a range variable declared twice in a query, or over a local or parameter), CS0841 / CS0844 (a local used above its
 *   declaration; CS0844 where the type has a field of that name);
 * - parameters: CS0100;
 * - members: CS0102 (a name twice in a type, an indexer against a member named `Item`), CS0111 (a signature twice: methods, constructors,
 *   indexers, operators by their metadata names, explicit implementations by the interface's name), CS0663 (overloads differing in
 *   `ref` / `out` / `in` only), CS0557 (a conversion twice, `implicit` against `explicit` too), CS0756 / CS0757 (a partial method defined or
 *   implemented twice), CS8646 (an interface member implemented explicitly twice);
 * - types: CS0101 (a type twice in a namespace, other files of the project in their compilation order, [CSharpCompileOrder]), CS0264
 *   (parts of a partial type naming their type parameters differently).
 *
 * The scopes are those of [io.github.dotnetsupport.lang.NativeCSharpScopes], walked outward as Roslyn's binders check a new name: the
 * first scope that has it decides, a local of a scope before its local functions, and the walk stops at a lambda, a local function or a
 * query clause (C# 8 lets those reuse an outer name). Silent where the answer is not sure: a query clause seeing several range variables
 * (Roslyn passes them in a transparent identifier there, so a nested name may or may not clash), the first `from` and `join` expressions,
 * `_` but of `var _ = 1;`, a variable of a constructor initializer, unresolved parameter types, a type declared twice (Roslyn then skips its
 * members), a type with generated parts, a file whose place in the compilation is not known.
 */
internal class CSharpDeclarationChecks(
    private val resolver: CSharpNameResolver,
    private val quiet: (PsiElement) -> Boolean,
    private val checks: CSharpSemanticChecks,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val file: CSharpFile = resolver.file
    private val scopes = resolver.syntax.scopes

    fun run() {
        checkLocals()
        checkParameters()
        for (declaration in scopes.declarations) {
            if (declaration is CSharpTypeDeclaration && declaration.node.elementType != SyntaxKind.ExtensionBlockDeclaration || declaration is CSharpEnumDeclaration) {
                checkMembers(declaration as CSharpBaseTypeDeclaration)
            }
            if (declaration is CSharpTypeDeclaration) checkTypeParameterNames(declaration)
        }
        checkNamespaceTypes()
    }

    // ---- locals: CS0128, CS0136, CS1930, CS1931, CS0841, CS0844

    private enum class Verdict { NONE, SILENT, CS0128, CS0136, CS1930, CS1931 }

    private var byScope: Map<PsiElement, List<LocalSymbol>> = emptyMap()

    private fun checkLocals() {
        val symbols = scopes.symbols.filter { (it.kind == LocalSymbolKind.LOCAL || it.kind == LocalSymbolKind.LOCAL_FUNCTION || it.kind == LocalSymbolKind.PARAMETER) && !inConstructorInitializer(it.declaration) }
        byScope = symbols.groupBy { it.scope }
        val reported = HashSet<LocalSymbol>()
        for (symbol in symbols) {
            if (symbol.kind == LocalSymbolKind.PARAMETER) continue
            val verdict = when {
                isRangeVariable(symbol) -> rangeVariableVerdict(symbol)
                isQuery(symbol.scope) -> clauseVariableVerdict(symbol)
                else -> localVerdict(symbol)
            }
            if (verdict == Verdict.NONE || verdict == Verdict.SILENT || quiet(statementOf(symbol.declaration))) continue
            reported += symbol
            val name = symbol.name
            when (verdict) {
                Verdict.CS0128 -> report("CS0128", "A local variable or function named '$name' is already defined in this scope", symbol.declaration.textRange)
                Verdict.CS0136 -> declaredInEnclosing(symbol)
                Verdict.CS1930 -> report("CS1930", "The range variable '$name' has already been declared", symbol.declaration.textRange)
                Verdict.CS1931 -> report("CS1931", "The range variable '$name' conflicts with a previous declaration of '$name'", symbol.declaration.textRange)
                else -> {}
            }
        }
        for (symbol in symbols) {
            if (symbol.kind != LocalSymbolKind.LOCAL || symbol.name == "_" || isRangeVariable(symbol)) continue
            if (symbol.name == "value" && symbol !in reported && !quiet(statementOf(symbol.declaration)) && inValueAccessor(symbol.scope)) declaredInEnclosing(symbol)
            // declared twice: the uses are bound to one of them, not surely the right one
            if (byScope[symbol.scope].orEmpty().none { it !== symbol && it.name == symbol.name }) checkOrder(symbol)
        }
    }

    private fun declaredInEnclosing(symbol: LocalSymbol) = report("CS0136",
        "A local or parameter named '${symbol.name}' cannot be declared in this scope because that name is used in an enclosing local scope to define a local or parameter",
        symbol.declaration.textRange)

    private fun offset(symbol: LocalSymbol): Int = symbol.declaration.textRange.startOffset

    /**
     * A local or local function, as Roslyn's `ValidateDeclarationNameConflictsInScope`: in its own scope the first local of the name (else
     * the first local function) is the one declared — another one at or after it is CS0128, one before it CS0136; an enclosing scope of the
     * same function that has the name, its parameters included, is CS0136.
     */
    private fun localVerdict(symbol: LocalSymbol): Verdict {
        val name = symbol.name
        if (name == "_") return discardVerdict(symbol)
        var element: PsiElement? = symbol.scope
        var own = true
        while (element != null && element !is PsiFile) {
            val here = byScope[element]
            if (here != null) {
                if (isQuery(element)) return clauseVerdict(element, symbol.declaration, name, Verdict.CS0136, nested = true)
                val locals = here.filter { it.name == name && it.kind != LocalSymbolKind.PARAMETER }
                if (own) {
                    val declared = locals.filter { it.kind == LocalSymbolKind.LOCAL }.minByOrNull(::offset) ?: locals.minByOrNull(::offset)
                    if (declared != null && declared !== symbol) return if (offset(symbol) >= offset(declared)) Verdict.CS0128 else Verdict.CS0136
                } else if (locals.isNotEmpty()) {
                    return Verdict.CS0136
                }
                if (here.any { it.kind == LocalSymbolKind.PARAMETER && it.name == name }) return Verdict.CS0136
            }
            if (isFunctionBoundary(element)) return Verdict.NONE
            own = false
            element = element.parent
        }
        return Verdict.NONE
    }

    /** `_`: a local only as a declarator (`var _ = 1;` twice is CS0128); a discard elsewhere, and never said to hide anything. */
    private fun discardVerdict(symbol: LocalSymbol): Verdict {
        val same = byScope[symbol.scope].orEmpty().filter { it.name == "_" && it.kind != LocalSymbolKind.PARAMETER }
        if (same.any { it.declaration.parent !is CSharpVariableDeclarator }) return Verdict.SILENT
        return if (same.minByOrNull(::offset) !== symbol) Verdict.CS0128 else Verdict.NONE
    }

    /** A range variable: CS1930 after one of its name in the same query (a continuation starts anew), else CS1931 over a local or parameter around the query. */
    private fun rangeVariableVerdict(symbol: LocalSymbol): Verdict {
        val name = symbol.name
        if (byScope[symbol.scope].orEmpty().any { it !== symbol && isRangeVariable(it) && it.name == name && offset(it) < offset(symbol) }) return Verdict.CS1930
        var query: PsiElement = symbol.scope
        while (query !is CSharpQueryExpression) query = query.parent ?: return Verdict.SILENT
        var element: PsiElement? = query.parent
        while (element != null && element !is PsiFile) {
            val here = byScope[element]
            if (here != null) {
                if (isQuery(element)) return clauseVerdict(element, query, name, Verdict.CS1931, nested = true)
                if (here.any { it.name == name }) return Verdict.CS1931
            }
            if (isFunctionBoundary(element)) return Verdict.NONE
            element = element.parent
        }
        return Verdict.NONE
    }

    /** A variable declared in an expression of a query clause (`where int.TryParse(s, out var n)`): the clause is a lambda of its own. */
    private fun clauseVariableVerdict(symbol: LocalSymbol): Verdict {
        val clause = clauseOf(symbol.scope, symbol.declaration) ?: return Verdict.SILENT
        val same = byScope[symbol.scope].orEmpty().filter { !isRangeVariable(it) && it.name == symbol.name && clause.textRange.contains(it.declaration.textRange) }
        if (same.minByOrNull(::offset) !== symbol) return Verdict.CS0128
        return clauseVerdict(symbol.scope, symbol.declaration, symbol.name, Verdict.CS0136, nested = false)
    }

    /**
     * A name declared in (or below) a clause of [query] meets the range variables that clause's lambda takes. With one, it is the lambda's
     * parameter and the name clashes with it ([code]); with several Roslyn passes some of them in a transparent identifier, whose members
     * are no parameters: `from a in x let b = a select (from a in y ...)` is fine, `from a in x from b in y select (from a in z ...)` is not.
     */
    private fun clauseVerdict(query: PsiElement, inner: PsiElement, name: String, code: Verdict, nested: Boolean): Verdict {
        val clause = clauseOf(query, inner) ?: return Verdict.SILENT
        val here = byScope[query].orEmpty()
        if (nested && here.any { !isRangeVariable(it) && it.name == name && clause.textRange.contains(it.declaration.textRange) }) return Verdict.CS0136
        val visible = here.filter { isRangeVariable(it) && offset(it) < clause.textRange.startOffset }
        if (visible.none { it.name == name }) return Verdict.NONE
        return if (visible.size == 1) code else Verdict.SILENT
    }

    /** The clause of [query] (a query expression or continuation) [inner] is in; null in the first `from` or a `join`, which are bound around the query. */
    private fun clauseOf(query: PsiElement, inner: PsiElement): PsiElement? {
        val body = when (query) {
            is CSharpQueryExpression -> query.body
            is CSharpQueryContinuation -> query.body
            else -> null
        } ?: return null
        val clause = (body.clauses + listOfNotNull(body.selectOrGroup)).firstOrNull { PsiTreeUtil.isAncestor(it, inner, false) } ?: return null
        return clause.takeIf { it !is CSharpJoinClause }
    }

    private fun isQuery(element: PsiElement): Boolean = element is CSharpQueryExpression || element is CSharpQueryContinuation

    private fun isFunctionBoundary(element: PsiElement): Boolean = element is CSharpAnonymousFunctionExpression || element is CSharpLocalFunctionStatement ||
        element is CSharpMemberDeclaration && element !is CSharpGlobalStatement

    /** A local named `value` in a `set`, `init`, `add` or `remove` accessor, not in a function nested in it: the implicit parameter is there. */
    private fun inValueAccessor(scope: PsiElement): Boolean {
        var at: PsiElement? = scope
        while (at != null && at !is CSharpFile) {
            if (at is CSharpAccessorDeclaration) return at.keyword?.text.let { it == "set" || it == "init" || it == "add" || it == "remove" }
            if (isFunctionBoundary(at) || isQuery(at)) return false
            at = at.parent
        }
        return false
    }

    /**
     * CS0841 / CS0844: a use of [symbol] above its declaration, in the same member (a use in a lambda or local function too, as Roslyn).
     * Roslyn looks the name up in the type around: a single field is CS0844 naming it, anything else (nothing, a property, methods) CS0841.
     */
    private fun checkOrder(symbol: LocalSymbol) {
        val declaredAt = symbol.declaration.textRange.startOffset
        var hidden: Hidden? = null
        for (leaf in symbol.references) {
            if (leaf.textRange.startOffset >= declaredAt) continue
            val name = leaf.parent as? CSharpSimpleName ?: continue
            if (quiet(name) || colorColor(name, symbol)) continue
            if (PsiTreeUtil.getParentOfType(name, CSharpMemberDeclaration::class.java)?.let { it !is CSharpGlobalStatement && !PsiTreeUtil.isAncestor(it, symbol.declaration, true) } == true) continue
            if (hidden == null) hidden = hiddenField(symbol)
            when (hidden) {
                Hidden.Unknown -> return
                Hidden.None -> report("CS0841", "Cannot use local variable '${symbol.name}' before it is declared", leaf.textRange)
                is Hidden.Field -> report("CS0844",
                    "Cannot use local variable '${symbol.name}' before it is declared. The declaration of the local variable hides the field '${hidden.shown}'.", leaf.textRange)
            }
        }
    }

    private sealed class Hidden {
        object Unknown : Hidden()
        object None : Hidden()
        class Field(val shown: String) : Hidden()
    }

    /** What the type around [symbol] has of its name, as Roslyn's `LookupMembersInType` for CS0844 finds it. */
    private fun hiddenField(symbol: LocalSymbol): Hidden {
        val name = symbol.name
        val info = resolver.syntax.enclosingTypes(symbol.declaration).firstOrNull()
        // top-level statements: the members of the generated `Program`, none unless a part of it is declared
        if (info == null) return if (resolver.syntax.typeParts("Program").any { it.namespace == "" }) Hidden.Unknown else Hidden.None
        if (checks.isPartial(info)) return Hidden.Unknown
        val self = resolver.selfType(info)
        if (!checks.isKnown(self)) return Hidden.Unknown
        val found = resolver.membersNamed(self, name, 0)
        if (found.isEmpty()) return if (checks.has(self, name)) Hidden.Unknown else Hidden.None
        val single = found.singleOrNull() ?: return Hidden.None
        return when (single) {
            is CSharpSymbol.SourceMember -> {
                val field = single.element.let { it as? CSharpBaseFieldDeclaration ?: it.parent?.parent as? CSharpBaseFieldDeclaration } ?: return Hidden.None
                if (field is CSharpEventFieldDeclaration) return Hidden.None
                val owner = field.parent as? CSharpBaseTypeDeclaration ?: return Hidden.Unknown
                // a private field of a base type is not found from here: not sure what Roslyn makes of it
                if (resolver.syntax.declaredType(owner)?.key != info.key && field.modifiers.none { it.text == "public" || it.text == "protected" || it.text == "internal" }) return Hidden.Unknown
                typeDisplay(owner)?.let { Hidden.Field("$it.$name") } ?: Hidden.Unknown
            }
            is CSharpSymbol.SourceType -> Hidden.None
            is CSharpSymbol.LibraryMember -> if (single.member.kind == io.github.dotnetsupport.index.IndexedMemberKind.FIELD ||
                single.member.kind == io.github.dotnetsupport.index.IndexedMemberKind.CONSTANT) Hidden.Unknown else Hidden.None
            else -> Hidden.Unknown
        }
    }

    /** `Color.Red` above a local `Color Color`: the name may be the type there (C# §12.8.7.2). */
    private fun colorColor(name: CSharpSimpleName, symbol: LocalSymbol): Boolean {
        val access = name.parent as? CSharpMemberAccessExpression ?: return false
        if (access.expression != name) return false
        val type = (symbol.declaration.parent?.parent as? CSharpVariableDeclaration)?.type ?: return true
        return type.text.substringBefore('<').substringAfterLast('.').trim() == symbol.name || type.text == "var"
    }

    private fun isRangeVariable(symbol: LocalSymbol): Boolean = symbol.declaration.parent.let {
        it is CSharpFromClause || it is CSharpLetClause || it is CSharpJoinClause || it is CSharpJoinIntoClause || it is CSharpQueryContinuation
    }

    private fun inConstructorInitializer(element: PsiElement): Boolean = PsiTreeUtil.getParentOfType(element, CSharpConstructorInitializer::class.java, CSharpMemberDeclaration::class.java) is CSharpConstructorInitializer

    /** The statement (or member) a declaration is in: what [quiet] is asked about, a pattern variable being quiet by itself. */
    private fun statementOf(element: PsiElement): PsiElement =
        PsiTreeUtil.getParentOfType(element, CSharpStatement::class.java, CSharpMemberDeclaration::class.java) ?: element

    // ---- CS0100

    /** Every parameter list: a name twice is CS0100 on each later one; `_` of a lambda or anonymous method is a discard (C# 9). */
    private fun checkParameters() {
        val lists = scopes.symbols.filter { it.kind == LocalSymbolKind.PARAMETER || it.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER }
            .mapNotNull { (it.declaration.parent as? CSharpParameter)?.parent as? CSharpBaseParameterList }.distinct()
        for (list in lists) {
            if (quiet(list.parent ?: list)) continue
            val lambda = list.parent is CSharpAnonymousFunctionExpression
            val seen = HashSet<String>()
            for (parameter in list.parameters) {
                val identifier = parameter.identifier?.takeIf { it.node.elementType == SyntaxKind.IdentifierToken } ?: continue
                val name = identifier.text
                if (lambda && name == "_") continue
                if (!seen.add(name)) report("CS0100", "The parameter name '$name' is a duplicate", identifier.textRange)
            }
        }
    }

    // ---- members: CS0102, CS0111, CS0663, CS0557, CS0756, CS0757, CS8646

    private enum class Kind { TYPE, METHOD, OTHER }

    /**
     * A member as Roslyn's name conflict checks see it: [name] its metadata name (`op_Addition`, `Ns.IFoo.Run` for an explicit
     * implementation), [shown] what CS0111 calls it (`this` for an indexer, the type for a constructor), [at] what gets the error.
     */
    private class Entry(
        val name: String, val kind: Kind, val at: PsiElement, val declaration: PsiElement, val arity: Int = 0, val partial: Boolean = false,
        val shown: String = name, val conversion: Boolean = false, val implemented: SemanticType? = null,
    )

    /** What one pass over the members of a type collects besides [entries]. */
    private class Members {
        val entries = ArrayList<Entry>()
        val partialMethods = ArrayList<CSharpMethodDeclaration>()
        /** Indexers that are no explicit implementation, with the name they have in metadata (`Item`, `[IndexerName]`), null when not known. */
        val indexers = ArrayList<Pair<CSharpIndexerDeclaration, String?>>()
        val unsure = HashSet<String>()
    }

    private fun checkMembers(type: CSharpBaseTypeDeclaration) {
        if (quiet(type)) return
        val typeName = type.identifier?.text ?: return
        // a type declared twice: Roslyn says CS0101 / CS0102 and leaves its members alone
        val info = resolver.syntax.declaredType(type) ?: return
        if (info.parts.size > 1 && info.parts.any { "partial" !in it.modifiers }) return
        if (generateSequence(type.parent) { it.parent }.takeWhile { it !is CSharpFile }.filterIsInstance<CSharpBaseTypeDeclaration>().any { outer ->
                resolver.syntax.declaredType(outer)?.parts?.let { parts -> parts.size > 1 && parts.any { "partial" !in it.modifiers } } != false
            }) return
        val partialType = "partial" in type.modifiers.map { it.text }
        val parts = if (partialType) {
            info.parts.mapNotNull { (it as? TypePart.Psi)?.declaration as? CSharpBaseTypeDeclaration }.filter { it.containingFile == file }.sortedBy { it.textRange.startOffset }
        } else listOf(type)
        // the first part of this file says it all
        if (parts.firstOrNull() != type) return
        if (parts.any { it != type && quiet(it) }) return
        val shown = typeDisplay(type) ?: return
        val members = Members()
        parts.forEach { collect(it, members) }
        val record = type as? CSharpRecordDeclaration
        // the parts of other files are not seen: a positional parameter may have its member there
        if (record != null && (!partialType || info.parts.size == 1)) positionalMembers(record, members)
        checkPartialMethods(members, shown)
        val entries = members.entries.sortedBy { it.declaration.textRange.startOffset }.let { sorted ->
            // a synthesized member of a positional parameter comes before the members written in the record
            sorted.filter { it.declaration is CSharpParameter } + sorted.filter { it.declaration !is CSharpParameter }
        }
        val byName = entries.groupBy { it.name }
        val implementedTwice = LinkedHashMap<String, Entry>()
        for ((name, all) in byName) {
            if (name == typeName || name == ".ctor" || name in members.unsure) continue
            val types = all.filter { it.kind == Kind.TYPE }
            for ((_, same) in types.groupBy { it.arity }) if (same.none { it.partial }) same.drop(1).forEach { duplicate(shown, it.name, it.at) }
            var last: Entry? = types.firstOrNull()
            for (entry in all) {
                if (entry.kind == Kind.TYPE) continue
                val previous = last
                if (previous == null) { last = entry; continue }
                if (entry.kind != Kind.METHOD || previous.kind != Kind.METHOD) {
                    duplicate(shown, entry.name, entry.at)
                    if (entry.implemented != null && previous.implemented != null) implementedTwice.putIfAbsent(name, entry)
                    if (previous.kind == Kind.METHOD) last = entry
                }
            }
        }
        // the name of an indexer is reserved for indexers, wherever the other member is (on the indexer, as Roslyn)
        for ((indexer, name) in members.indexers) {
            if (name == null || name in members.unsure || byName[name].isNullOrEmpty()) continue
            indexer.thisKeyword?.let { duplicate(shown, name, it) }
        }
        for ((name, all) in byName) {
            if (name == typeName || name in members.unsure) continue
            val methods = all.filter { it.kind == Kind.METHOD }
            if (methods.size >= 2) checkSignatures(methods, shown, implementedTwice)
        }
        checkConversions(entries.filter { it.conversion }, shown)
        for (entry in implementedTwice.values) implementedTwice(entry, type)
    }

    private fun duplicate(shown: String, name: String, at: PsiElement) = report("CS0102", "The type '$shown' already contains a definition for '$name'", at.textRange)

    /** The members of one part as Roslyn names them; accessors and static constructors are no names of their own. */
    private fun collect(part: CSharpBaseTypeDeclaration, out: Members) {
        if (part is CSharpEnumDeclaration) {
            for (member in part.members) member.identifier?.let { out.entries += Entry(it.text, Kind.OTHER, it, member) }
            return
        }
        val type = part as? CSharpTypeDeclaration ?: return
        val record = type is CSharpRecordDeclaration
        // a primary constructor comes first, unless another part of a partial type is before it; a record's is its positional one
        if (type.modifiers.none { it.text == "partial" } || record) type.parameterList?.let { out.entries += Entry(".ctor", Kind.METHOD, type.identifier ?: return@let, type, shown = type.identifier!!.text) }
        for (member in type.members) {
            val modifiers = member.modifiers.map { it.text }
            val partial = "partial" in modifiers
            when (member) {
                is CSharpMethodDeclaration -> {
                    val identifier = member.identifier ?: continue
                    val explicit = member.explicitInterfaceSpecifier
                    when {
                        explicit != null -> explicitName(explicit)?.let { (full, iface) ->
                            out.entries += Entry("$full.${identifier.text}", Kind.METHOD, identifier, member, implemented = iface)
                        }
                        partial -> out.partialMethods += member
                        else -> out.entries += Entry(identifier.text, Kind.METHOD, identifier, member)
                    }
                }
                is CSharpConstructorDeclaration -> if ("static" !in modifiers && !partial) member.identifier?.let { out.entries += Entry(".ctor", Kind.METHOD, it, member, shown = it.text) }
                is CSharpOperatorDeclaration -> {
                    val token = member.operatorToken ?: continue
                    val name = (if (member.explicitInterfaceSpecifier != null) null else operatorName(member)) ?: continue
                    out.entries += Entry(name, Kind.METHOD, token, member)
                }
                is CSharpConversionOperatorDeclaration -> {
                    val keyword = member.implicitOrExplicitKeyword?.text ?: continue
                    if (member.explicitInterfaceSpecifier != null || member.checkedKeyword != null) { out.unsure += "op_Implicit"; out.unsure += "op_Explicit"; continue }
                    val at = member.type ?: continue
                    out.entries += Entry(if (keyword == "implicit") "op_Implicit" else "op_Explicit", Kind.METHOD, at, member, conversion = true)
                }
                is CSharpIndexerDeclaration -> {
                    val at = member.thisKeyword ?: continue
                    if (partial) { out.unsure += "this"; continue }
                    val explicit = member.explicitInterfaceSpecifier
                    if (explicit != null) {
                        explicitName(explicit)?.let { (full, iface) -> out.entries += Entry("$full.this[]", Kind.METHOD, at, member, shown = "this", implemented = iface) }
                    } else {
                        out.entries += Entry("this[]", Kind.METHOD, at, member, shown = "this")
                        out.indexers += member to indexerName(member)
                    }
                }
                is CSharpPropertyDeclaration -> {
                    val identifier = member.identifier ?: continue
                    if (partial) { out.unsure += identifier.text; continue }
                    val explicit = member.explicitInterfaceSpecifier
                    if (explicit == null) out.entries += Entry(identifier.text, Kind.OTHER, identifier, member)
                    else explicitName(explicit)?.let { (full, iface) -> out.entries += Entry("$full.${identifier.text}", Kind.OTHER, identifier, member, implemented = iface) }
                }
                is CSharpEventDeclaration -> {
                    val identifier = member.identifier ?: continue
                    if (partial) { out.unsure += identifier.text; continue }
                    val explicit = member.explicitInterfaceSpecifier
                    if (explicit == null) out.entries += Entry(identifier.text, Kind.OTHER, identifier, member)
                    else explicitName(explicit)?.let { (full, iface) -> out.entries += Entry("$full.${identifier.text}", Kind.OTHER, identifier, member, implemented = iface) }
                }
                is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().forEach { v -> v.identifier?.let { out.entries += Entry(it.text, Kind.OTHER, it, v) } }
                is CSharpBaseTypeDeclaration -> if (member.node.elementType != SyntaxKind.ExtensionBlockDeclaration) member.identifier?.let {
                    out.entries += Entry(it.text, Kind.TYPE, it, member, TypePart.typeParameters(member), partial)
                }
                is CSharpDelegateDeclaration -> member.identifier?.let { out.entries += Entry(it.text, Kind.TYPE, it, member, TypePart.typeParameters(member)) }
                else -> {}
            }
        }
    }

    /**
     * The property a positional parameter of a record makes, unless a field or property of the name is written (then that one is the
     * member, CS8866 when it does not fit — not said here): a method or event of the name is CS0102, a nested type puts it on the parameter.
     */
    private fun positionalMembers(record: CSharpRecordDeclaration, out: Members) {
        val written = out.entries.filter { e ->
            e.declaration is CSharpVariableDeclarator && e.declaration.parent?.parent !is CSharpEventFieldDeclaration || e.declaration is CSharpPropertyDeclaration
        }.mapTo(HashSet()) { it.name }
        // a member of the base record takes the parameter instead: not sure what is made then
        val base = record.baseList?.types?.firstOrNull()?.let { it.type?.let(resolver::resolveType) ?: return@let null }
        val baseUnknown = record.baseList?.types?.isNotEmpty() == true && (base == null || !checks.isKnown(base))
        for (parameter in record.parameterList?.parameters.orEmpty()) {
            val identifier = parameter.identifier?.takeIf { it.node.elementType == SyntaxKind.IdentifierToken } ?: continue
            if (identifier.text in written) continue
            if (baseUnknown || base != null && checks.has(base, identifier.text)) { out.unsure += identifier.text; continue }
            out.entries += Entry(identifier.text, Kind.OTHER, identifier, parameter)
        }
    }

    /**
     * Partial methods of the parts of this file, by signature: a second defining declaration is CS0756 and CS0111, a second implementing
     * one CS0757. A definition and its implementation are one member, at the definition (an implementation alone stands for itself).
     */
    private fun checkPartialMethods(out: Members, shown: String) {
        for ((name, methods) in out.partialMethods.groupBy { it.identifier!!.text }) {
            val signatures = methods.map { signature(it) }
            if (signatures.any { it == null }) { out.unsure += name; continue }
            for ((_, group) in methods.zip(signatures).groupBy({ it.second!! }, { it.first })) {
                val (definitions, implementations) = group.sortedBy { it.textRange.startOffset }.partition { it.body == null && it.expressionBody == null }
                for (definition in definitions.drop(1)) {
                    val at = definition.identifier!!
                    report("CS0756", "A partial method may not have multiple defining declarations", at.textRange)
                    report("CS0111", "Type '$shown' already defines a member called '$name' with the same parameter types", at.textRange)
                }
                for (implementation in implementations.drop(1)) report("CS0757", "A partial method may not have multiple implementing declarations", implementation.identifier!!.textRange)
                val member = definitions.firstOrNull() ?: implementations.first()
                // where Roslyn places the pair among the other members is not sure when something of the name lies between them
                val first = implementations.firstOrNull()
                if (first != null && definitions.isNotEmpty() && out.entries.any { e -> e.name == name && e.kind != Kind.METHOD &&
                        e.declaration.textRange.startOffset in minOf(first.textRange.startOffset, member.textRange.startOffset)..maxOf(first.textRange.startOffset, member.textRange.startOffset) }) out.unsure += name
                out.entries += Entry(name, Kind.METHOD, member.identifier!!, member)
            }
        }
    }

    /**
     * CS0111: a method (constructor, indexer, operator) whose type parameters count and parameter types are those of an earlier one;
     * `params`, `this`, `scoped`, names, return types and nullable annotations do not count. Only `ref` / `out` / `in` / `ref readonly`
     * against each other is CS0663, against the first such overload. A conversion against a conversion is CS0557 instead ([checkConversions]).
     */
    private fun checkSignatures(methods: List<Entry>, shown: String, implementedTwice: MutableMap<String, Entry>) {
        val signatures = methods.map { signature(it.declaration) }
        for (i in 1 until methods.size) {
            val own = signatures[i] ?: continue
            val entry = methods[i]
            val j = (0 until i).firstOrNull { j -> signatures[j]?.types == own.types && signatures[j]!!.byRef == own.byRef && !(entry.conversion && methods[j].conversion) } ?: continue
            val other = signatures[j]!!
            if (other.refs == own.refs) {
                report("CS0111", "Type '$shown' already defines a member called '${entry.shown}' with the same parameter types", entry.at.textRange)
                if (entry.implemented != null) implementedTwice.putIfAbsent(entry.name + own.types, entry)
                continue
            }
            val what = when (entry.declaration) {
                is CSharpMethodDeclaration -> "method"
                is CSharpConstructorDeclaration -> "constructor"
                else -> continue
            }
            val at = own.refs.indices.first { own.refs[it] != other.refs[it] }
            report("CS0663", "'$shown' cannot define an overloaded $what that differs only on parameter modifiers '${own.refs[at]}' and '${other.refs[at]}'", entry.at.textRange)
        }
    }

    /** CS0557: two conversions between the same types, `implicit` or `explicit`; on the later one's type. */
    private fun checkConversions(conversions: List<Entry>, shown: String) {
        val seen = HashSet<Pair<List<String>, String>>()
        for (entry in conversions) {
            val declaration = entry.declaration as CSharpConversionOperatorDeclaration
            val types = signature(declaration)?.types ?: continue
            val target = declaration.type?.let(resolver::resolveType)?.let(::key) ?: continue
            if (!seen.add(types to target)) report("CS0557", "Duplicate user-defined conversion in type '$shown'", entry.at.textRange)
        }
    }

    /**
     * CS8646 on the type, once per interface member: it is implemented explicitly more than once. Only where the interface is declared in
     * this file, listed by the type and has that member (else Roslyn says CS0539 / CS0540), and the member reads plainly (`IFoo.Run(int)`).
     */
    private fun implementedTwice(entry: Entry, type: CSharpBaseTypeDeclaration) {
        if ("partial" in type.modifiers.map { it.text }) return
        val iface = entry.implemented as? SemanticType.Source ?: return
        if (iface.arguments.isNotEmpty() || iface.outer != null) return
        val declarations = iface.info.parts.mapNotNull { (it as? TypePart.Psi)?.declaration as? CSharpTypeDeclaration }
        if (declarations.isEmpty() || declarations.any { it.containingFile != file } || iface.info.parts.size != declarations.size) return
        if (type.baseList?.types.orEmpty().none { base -> (base.type?.let(resolver::resolveType) as? SemanticType.Source)?.info?.key == iface.info.key }) return
        val own = signature(entry.declaration)
        val members = declarations.flatMap { it.members }
        val name = entry.at.text
        val member = members.firstOrNull { candidate ->
            when (entry.declaration) {
                is CSharpMethodDeclaration -> candidate is CSharpMethodDeclaration && candidate.identifier?.text == name && own != null && signature(candidate)?.let { it.types == own.types && it.refs == own.refs } == true
                is CSharpIndexerDeclaration -> candidate is CSharpIndexerDeclaration && own != null && signature(candidate)?.let { it.types == own.types && it.refs == own.refs } == true
                is CSharpPropertyDeclaration -> candidate is CSharpPropertyDeclaration && candidate.explicitInterfaceSpecifier == null && candidate.identifier?.text == name
                is CSharpEventDeclaration -> candidate is CSharpEventDeclaration && candidate.explicitInterfaceSpecifier == null && candidate.identifier?.text == name ||
                    candidate is CSharpEventFieldDeclaration && candidate.declaration?.variables.orEmpty().any { it.identifier?.text == name }
                else -> false
            }
        } ?: return
        val parameters = when (member) {
            is CSharpMethodDeclaration -> member.parameterList?.parameters
            is CSharpIndexerDeclaration -> member.parameterList?.parameters
            else -> emptyList()
        } ?: return
        val shownParameters = parameters.map { p ->
            if (p.modifiers.any { it.text != "ref" && it.text != "out" && it.text != "in" } || p.type?.text?.contains('?') != false) return
            val shownType = CSharpTypeDisplay.display(p.type?.let(resolver::resolveType), qualified = false) ?: return
            (p.modifiers.joinToString("") { it.text + " " }) + shownType
        }
        val ifaceShown = CSharpTypeDisplay.display(iface, qualified = false) ?: return
        val shown = when (member) {
            is CSharpMethodDeclaration -> "$ifaceShown.${member.identifier!!.text}" + (if (member.typeParameterList != null) return else "") + shownParameters.joinToString(", ", "(", ")")
            is CSharpIndexerDeclaration -> "$ifaceShown.this" + shownParameters.joinToString(", ", "[", "]")
            else -> "$ifaceShown.$name"
        }
        val at = type.identifier ?: return
        report("CS8646", "'$shown' is explicitly implemented more than once.", at.textRange)
    }

    /** `Ns.IFoo` (`Ns.IFoo<System.Int32>`) as Roslyn writes the interface in the name of an explicit implementation, with the type; null when not resolved. */
    private fun explicitName(specifier: CSharpExplicitInterfaceSpecifier): Pair<String, SemanticType>? {
        val type = specifier.nameElement?.let(resolver::resolveType) ?: return null
        return metadataName(type)?.let { it to type }
    }

    private fun metadataName(type: SemanticType): String? = when (type) {
        is SemanticType.Source -> if (type.outer?.arguments?.isNotEmpty() == true) null
            else if (type.arguments.isEmpty()) type.info.qualifiedName
            else type.arguments.map { it?.let(::metadataName) ?: return null }.joinToString(",", type.info.qualifiedName + "<", ">")
        is SemanticType.Library -> type.type.fullName.takeIf { type.arguments.isEmpty() && '+' !in it && '`' !in it }
        is SemanticType.Parameter -> type.name
        is SemanticType.ArrayOf -> if (type.rank == 1) type.element?.let(::metadataName)?.let { "$it[]" } else null
    }

    /** `[IndexerName("Entry")]` names an indexer in metadata, `Item` by default; null when an attribute may name it in a way not read here. */
    private fun indexerName(indexer: CSharpIndexerDeclaration): String? {
        var name = "Item"
        for (attribute in indexer.attributeLists.flatMap { it.attributes }) {
            val simple = attribute.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute") ?: return null
            if (simple != "IndexerName") continue
            val argument = attribute.argumentList?.arguments?.singleOrNull()?.expression as? CSharpLiteralExpression ?: return null
            name = argument.text.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') && '\\' !in it }?.removeSurrounding("\"") ?: return null
        }
        return name
    }

    /** The metadata name of a user-defined operator (`op_Addition`, `op_UnaryNegation`, `op_CheckedMultiply`, `op_AdditionAssignment`), null for a form not modelled. */
    private fun operatorName(operator: CSharpOperatorDeclaration): String? {
        val token = operator.operatorToken?.text ?: return null
        val count = operator.parameterList?.parameters?.size ?: return null
        val static = operator.modifiers.any { it.text == "static" }
        val name = when {
            !static && count == 1 -> COMPOUND[token]
            static && count == 1 -> UNARY[token]
            static && count == 2 -> BINARY[token]
            else -> null
        } ?: return null
        if (operator.checkedKeyword == null) return "op_$name"
        return "op_Checked$name".takeIf { name in CHECKED }
    }

    private data class Signature(val types: List<String>, val refs: List<String>) {
        /** Which parameters are passed by reference: what tells overloads apart, the kind of reference does not (CS0663). */
        val byRef: List<Boolean> get() = refs.map { it.isNotEmpty() }
    }

    private fun signature(declaration: PsiElement): Signature? {
        val (arity, parameters) = when (declaration) {
            is CSharpMethodDeclaration -> (declaration.typeParameterList?.parameters?.size ?: 0) to declaration.parameterList?.parameters
            is CSharpConstructorDeclaration -> 0 to declaration.parameterList?.parameters
            is CSharpOperatorDeclaration -> 0 to declaration.parameterList?.parameters
            is CSharpConversionOperatorDeclaration -> 0 to declaration.parameterList?.parameters
            is CSharpTypeDeclaration -> 0 to declaration.parameterList?.parameters
            is CSharpIndexerDeclaration -> 0 to declaration.parameterList?.parameters
            else -> return null
        }
        if (parameters == null || parameters.any { it.identifier?.node?.elementType != SyntaxKind.IdentifierToken }) return null
        val types = listOf("`$arity") + parameters.map { p -> p.type?.let(resolver::resolveType)?.let(::key) ?: return null }
        val refs = parameters.map { p -> p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" || it == "readonly" }.joinToString(" ") }
        return Signature(types, refs)
    }

    /** The identity of a parameter type as overloads compare them: tuple names and nullable reference annotations aside. */
    private fun key(type: SemanticType?): String? = when (type) {
        null -> null
        is SemanticType.Parameter -> if (type.ofMethod) "!!${type.index}" else "!${type.name}"
        is SemanticType.ArrayOf -> key(type.element)?.let { "$it[${type.rank}]" }
        is SemanticType.Source -> type.arguments.map { key(it) ?: return null }.joinToString(",", "${type.outer?.let { key(it) ?: return null } ?: ""}/${type.info.key}<", ">")
        is SemanticType.Library -> type.arguments.map { key(it) ?: return null }.joinToString(",", "${type.type.fullName}<", ">")
    }

    /** `Outer<T>.Inner<U>`: a type as Roslyn's declaration errors name it, without its namespace. */
    private fun typeDisplay(type: CSharpBaseTypeDeclaration): String? {
        val names = ArrayList<String>()
        var at: PsiElement? = type
        while (at is CSharpBaseTypeDeclaration) {
            val name = at.identifier?.text ?: return null
            val parameters = (at as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.map { it.identifier?.text ?: return null }
            names += if (parameters.isNullOrEmpty()) name else name + parameters.joinToString(", ", "<", ">")
            at = at.parent
        }
        return names.asReversed().joinToString(".")
    }

    // ---- CS0264

    /**
     * CS0264, once per type, when some part names the type parameters otherwise than the first part (in the order of the compilation)
     * does: on the first part's name, the type shown as it writes it. Variance (`in` / `out`, CS1067 when only it differs) keeps it silent.
     */
    private fun checkTypeParameterNames(type: CSharpTypeDeclaration) {
        if ("partial" !in type.modifiers.map { it.text } || type.typeParameterList == null || quiet(type)) return
        val info = resolver.syntax.declaredType(type) ?: return
        val parts = info.parts.map { (it.element() as? CSharpTypeDeclaration)?.takeIf { "partial" in it.modifiers.map { m -> m.text } } ?: return }
        if (parts.size < 2) return
        val names = parts.map { part -> part.typeParameterList?.parameters?.map { p -> if (p.varianceKeyword != null) return; p.identifier?.text ?: return } ?: return }
        // the order: this file's parts by offset, another file's by the compilation order, known for every file or not at all
        val project = CSharpSemanticEnvironment.projectOf(file)
        val files = parts.map { it.containingFile }.distinct()
        if (files.any { CSharpSemanticEnvironment.projectOf(it) != project }) return
        // how many files come before each: the order of the compilation, as long as every pair is known
        val rank = files.associateWith { f -> files.count { g -> g != f && (fileBefore(g, f) ?: return) } }
        val ordered = parts.indices.sortedWith(compareBy({ rank.getValue(parts[it].containingFile) }, { parts[it].textRange.startOffset }))
        val first = ordered.first()
        if (ordered.none { names[it] != names[first] } || parts[first] != type) return
        val shown = typeDisplay(type) ?: return
        report("CS0264", "Partial declarations of '$shown' must have the same type parameter names in the same order", type.identifier?.textRange ?: return)
    }

    // ---- CS0101

    /**
     * A type declared twice in a namespace, by name and arity, none of the declarations partial or `file`: each after the first is CS0101,
     * the first one keeps quiet. The first may be in another file of the project (one it compiles): csc gets the files in the order of the
     * `Compile` items ([fileBefore]).
     */
    private fun checkNamespaceTypes() {
        val own = scopes.declarations.filter { (it is CSharpBaseTypeDeclaration || it is CSharpDelegateDeclaration) && it.node.elementType != SyntaxKind.ExtensionBlockDeclaration }
            .mapNotNull { declaration -> namespaceOf(declaration)?.let { Triple(it, declaration, CSharpDeclarationNames.nameElement(declaration)?.text ?: return@mapNotNull null) } }
        for ((key, group) in own.groupBy { (namespace, declaration, name) -> Triple(namespace, name, TypePart.typeParameters(declaration)) }) {
            val (namespace, name, arity) = key
            if (group.any { (_, d, _) -> (d as CSharpMemberDeclaration).modifiers.any { it.text == "partial" || it.text == "file" } || quiet(d) }) continue
            val ordered = group.map { it.second }.sortedBy { it.textRange.startOffset }
            val reported = when (elsewhere(namespace, name, arity)) {
                Elsewhere.PARTIAL -> continue
                Elsewhere.EARLIER -> ordered
                Elsewhere.NONE, Elsewhere.UNKNOWN -> ordered.drop(1)
            }
            val shown = namespace.ifEmpty { "<global namespace>" }
            for (declaration in reported) {
                val at = CSharpDeclarationNames.nameElement(declaration) ?: continue
                report("CS0101", "The namespace '$shown' already contains a definition for '$name'", at.textRange)
            }
        }
    }

    /** What other files of the project declare of a top-level type: nothing, a declaration csc surely gets first, one whose order is not sure, a partial one. */
    private enum class Elsewhere { NONE, EARLIER, UNKNOWN, PARTIAL }

    private fun elsewhere(namespace: String, name: String, arity: Int): Elsewhere {
        val project = CSharpSemanticEnvironment.projectOf(file)
        var result = Elsewhere.NONE
        for (part in resolver.syntax.typeParts(name)) {
            if (part.namespace != namespace || part.arity != arity || part.qualifiedName != (if (namespace.isEmpty()) name else "$namespace.$name")) continue
            val other = part.element()?.containingFile ?: return Elsewhere.PARTIAL
            if (other == file || CSharpSemanticEnvironment.projectOf(other) != project || "file" in part.modifiers) continue
            // a file the project leaves out (`<Compile Remove>`) is no declaration
            if (project != null && !isCompiled(project, other.viewProvider.virtualFile, compileFiles)) continue
            if ("partial" in part.modifiers) return Elsewhere.PARTIAL
            when (fileBefore(other, file)) {
                true -> result = Elsewhere.EARLIER
                null -> if (result == Elsewhere.NONE) result = Elsewhere.UNKNOWN
                false -> {}
            }
        }
        return result
    }

    /**
     * Whether csc gets [a] before [b] (two files of this file's project): by the `Compile` items MSBuild evaluated (or a project lists
     * without wildcards); else, in an SDK project whose items are its default glob, by [CSharpCompileOrder] — unless a `Compile Remove`
     * touches either file (a file removed and included again goes to the end). Null when not known.
     */
    private fun fileBefore(a: PsiFile, b: PsiFile): Boolean? {
        val first = a.viewProvider.virtualFile
        val second = b.viewProvider.virtualFile
        compileFiles?.let { compiled ->
            val i = compiled.indexOf(key(first.path))
            val j = compiled.indexOf(key(second.path))
            return if (i < 0 || j < 0) null else i < j
        }
        if (!globOrder) return null
        val project = CSharpSemanticEnvironment.projectOf(file)
        if (project != null) {
            val directory = project.parent ?: return null
            val removes = SolutionService.getInstance(file.project).msBuildProject(project).removes.filter { it.itemType.equals("Compile", ignoreCase = true) }
            for (f in listOf(first, second)) {
                val relative = VfsUtilCore.getRelativePath(f, directory, '/') ?: return null
                if (removes.any { it.glob.matches(MsBuildGlob.normalize(relative)) }) return null
            }
        }
        return CSharpCompileOrder.compare(first.path, second.path) < 0
    }

    /** The `Compile` items of the project in the order csc gets them, when they are known file by file (full paths, lower case). */
    private val compileFiles: List<String>? by lazy {
        val project = CSharpSemanticEnvironment.projectOf(file) ?: return@lazy null
        val options = CompilationModel.getInstance(file.project).options(project)
        options.compileFiles?.takeIf { files -> files.none { '*' in it || '?' in it } }?.map(::key)
    }

    /** Whether the files come from the SDK's default glob: an SDK project read statically, without its own `Compile` list (or no project, as in tests). */
    private val globOrder: Boolean by lazy {
        val project = CSharpSemanticEnvironment.projectOf(file) ?: return@lazy true
        val options = CompilationModel.getInstance(file.project).options(project)
        options.source == CompilationOptions.Source.STATIC && options.compileFiles == null && !SolutionService.getInstance(file.project).msBuildProject(project).isLegacy
    }

    private fun isCompiled(project: VirtualFile, other: VirtualFile, compiled: List<String>?): Boolean {
        if (compiled != null) return key(other.path) in compiled
        CompilationModel.getInstance(file.project).options(project).compiles(other.path)?.let { return it }
        val relative = VfsUtilCore.getRelativePath(other, project.parent ?: return false, '/') ?: return false
        return !ProjectContent(SolutionService.getInstance(file.project).msBuildProject(project)).isExcluded(relative)
    }

    private fun key(path: String): String = EvaluatedFiles.normalize(path).lowercase()

    /** The namespace of a type declared at the top of one (`""` for the global one); null for a nested type. */
    private fun namespaceOf(declaration: PsiElement): String? {
        val names = ArrayList<String>()
        var at: PsiElement? = declaration.parent
        while (at != null && at !is CSharpFile) {
            when (at) {
                is CSharpBaseNamespaceDeclaration -> names += CSharpDeclarationNames.name(at) ?: return null
                is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> return null
            }
            at = at.parent
        }
        return names.asReversed().joinToString(".")
    }

    private companion object {
        val UNARY = mapOf("+" to "UnaryPlus", "-" to "UnaryNegation", "!" to "LogicalNot", "~" to "OnesComplement", "++" to "Increment", "--" to "Decrement",
            "true" to "True", "false" to "False")
        val BINARY = mapOf("+" to "Addition", "-" to "Subtraction", "*" to "Multiply", "/" to "Division", "%" to "Modulus", "&" to "BitwiseAnd", "|" to "BitwiseOr",
            "^" to "ExclusiveOr", "<<" to "LeftShift", ">>" to "RightShift", ">>>" to "UnsignedRightShift", "==" to "Equality", "!=" to "Inequality",
            "<" to "LessThan", ">" to "GreaterThan", "<=" to "LessThanOrEqual", ">=" to "GreaterThanOrEqual")
        /** C# 14's instance compound assignments, `public void operator +=(T other)`. */
        val COMPOUND = mapOf("+=" to "AdditionAssignment", "-=" to "SubtractionAssignment", "*=" to "MultiplicationAssignment", "/=" to "DivisionAssignment",
            "%=" to "ModulusAssignment", "&=" to "BitwiseAndAssignment", "|=" to "BitwiseOrAssignment", "^=" to "ExclusiveOrAssignment",
            "<<=" to "LeftShiftAssignment", ">>=" to "RightShiftAssignment", ">>>=" to "UnsignedRightShiftAssignment")
        val CHECKED = setOf("Addition", "Subtraction", "Multiply", "Division", "UnaryNegation", "Increment", "Decrement")
    }
}
