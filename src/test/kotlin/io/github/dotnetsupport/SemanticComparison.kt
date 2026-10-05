package io.github.dotnetsupport

import io.github.dotnetsupport.SemanticDump.Name

/**
 * What a resolver says about one file, in the oracle's terms ([SemanticDump]): places are `path:offset` of declared names, as the dump
 * writes them. [SemanticModelAnswers] adapts a `CSharpSemanticModel` over PSI; tests answer directly.
 */
interface SemanticAnswers {
    fun symbolAt(offset: Int): SemanticAnswer?
    fun typeOf(start: Int, end: Int): String?
    fun diagnostics(): List<SemanticDump.Diagnostic> = emptyList()
}

/**
 * A resolved symbol: where it is declared (`path:offset`, every part) and/or its documentation comment id. [candidates]: the resolver could
 * not pick one (places and id empty then) — no answer, but for the names Roslyn could not bind either, where any candidate counts.
 */
class SemanticAnswer(val places: List<String>, val id: String? = null, val candidates: List<SemanticAnswer> = emptyList()) {
    val isCandidatesOnly: Boolean get() = places.isEmpty() && id == null

    override fun toString(): String = if (isCandidatesOnly) "candidates " + candidates.joinToString(" | ") { it.toString() } else places.ifEmpty { listOfNotNull(id) }.toString()
}

/**
 * The comparison of a resolver with Roslyn over the files of [SemanticDump]s, per layer of CSHARP_PSI_MIGRATION.md step 11 and per category:
 * 11a names (what an identifier binds to), 11b expression types, 11e diagnostics. A name is correct when the answer has a place among
 * the declarations of Roslyn's symbol and all its places are such, or the same documentation comment id; for candidates (Roslyn bound
 * none: overload resolution failed, inaccessible ...) any candidate counts. Names Roslyn does not bind are not scored: an answer for one
 * is counted apart as `answered unbound`.
 */
class SemanticComparison(private val examplesPerCategory: Int = 10) {
    class Tally {
        var total = 0
        var correct = 0
        var wrong = 0
        var unresolved = 0
        val examples = ArrayList<String>()

        fun percent(): String = if (total == 0) "-" else "%.1f".format(java.util.Locale.ROOT, 100.0 * correct / total)
    }

    val names = LinkedHashMap<String, Tally>().apply { NAME_CATEGORIES.forEach { put(it, Tally()) } }
    val types = LinkedHashMap<String, Tally>().apply { TYPE_CATEGORIES.forEach { put(it, Tally()) } }
    var files = 0
        private set
    var unboundNames = 0
        private set
    var answeredUnbound = 0
        private set
    var oracleErrors = 0
        private set
    var oracleWarnings = 0
        private set
    var matchedErrors = 0
        private set
    var matchedWarnings = 0
        private set
    var spuriousDiagnostics = 0
        private set
    /** Per code (task C4c): Roslyn's diagnostics of it, the resolver's that match, the resolver's that do not (false positives), examples of those. */
    val oracleByCode = java.util.TreeMap<String, Int>()
    val matchedByCode = java.util.TreeMap<String, Int>()
    val spuriousByCode = java.util.TreeMap<String, Int>()
    val spuriousExamples = ArrayList<String>()
    val missedExamples = ArrayList<String>()

    fun add(file: SemanticDump.FileRecord, answers: SemanticAnswers) {
        files++
        for (name in file.names) {
            val category = nameCategory(name)
            val answer = answers.symbolAt(name.offset)
            if (category == null) {
                unboundNames++
                if (answer != null && !answer.isCandidatesOnly) answeredUnbound++
                continue
            }
            val tally = names.getValue(category)
            tally.total++
            when {
                answer != null && answer.isCandidatesOnly && category == "candidates" && answer.candidates.any { matches(it, name.symbols) } -> tally.correct++
                answer == null || answer.isCandidatesOnly -> {
                    tally.unresolved++
                    example(tally, "${file.path}:${name.offset} ${name.text} unresolved${answer?.let { " ($it)" }.orEmpty()}, expected ${expected(name)}")
                }
                matches(answer, name.symbols) -> tally.correct++
                else -> { tally.wrong++; example(tally, "${file.path}:${name.offset} ${name.text} -> $answer, expected ${expected(name)}") }
            }
        }
        for (expression in file.expressions) {
            val expected = expression.type?.takeIf { !it.startsWith("?") } ?: continue
            val tally = types.getValue(typeCategory(expression.kind))
            tally.total++
            when (val actual = answers.typeOf(expression.start, expression.end)) {
                null -> { tally.unresolved++; example(tally, "${file.path}:${expression.start}-${expression.end} ${expression.kind} unresolved, expected $expected") }
                expected -> tally.correct++
                else -> { tally.wrong++; example(tally, "${file.path}:${expression.start}-${expression.end} ${expression.kind} $actual, expected $expected") }
            }
        }
        val expected = file.diagnostics.associateBy { Triple(it.code, it.start, it.end) }
        oracleErrors += file.diagnostics.count { it.isError }
        oracleWarnings += file.diagnostics.count { !it.isError }
        file.diagnostics.forEach { oracleByCode.merge(it.code, 1, Int::plus) }
        val produced = answers.diagnostics().distinctBy { Triple(it.code, it.start, it.end) }
        for (d in produced) {
            val match = expected[Triple(d.code, d.start, d.end)]
            if (match == null) {
                spuriousByCode.merge(d.code, 1, Int::plus)
                val near = file.diagnostics.filter { it.start <= d.end && d.start <= it.end }.joinToString { "${it.code} ${it.start}-${it.end}" }
                if (spuriousExamples.size < 200) spuriousExamples += "${file.path}:${d.start}-${d.end} ${d.code}" + if (near.isEmpty()) "" else " (Roslyn there: $near)"
            } else matchedByCode.merge(d.code, 1, Int::plus)
        }
        val answered = produced.mapTo(HashSet()) { Triple(it.code, it.start, it.end) }
        for (d in file.diagnostics) if (d.code in NATIVE_CODES && Triple(d.code, d.start, d.end) !in answered && missedExamples.size < 300) missedExamples += "${file.path}:${d.start}-${d.end} ${d.code}"
        for (d in produced) {
            val match = expected[Triple(d.code, d.start, d.end)]
            when {
                match == null -> spuriousDiagnostics++
                match.isError -> matchedErrors++
                else -> matchedWarnings++
            }
        }
    }

    private fun example(tally: Tally, text: String) {
        if (tally.examples.size < examplesPerCategory) tally.examples += text
    }

    private fun expected(name: Name): String = name.symbols.joinToString(" | ") { "${it.kind} ${it.id} ${it.declarations.joinToString(",")}" }

    /** Integer metrics for a baseline: correct answers per category (higher is better). */
    fun metrics(): Map<String, Int> = buildMap {
        names.forEach { (category, tally) -> if (tally.total > 0) put("names.$category", tally.correct) }
        types.forEach { (category, tally) -> if (tally.total > 0) put("types.$category", tally.correct) }
        put("diagnostics.matched", matchedErrors + matchedWarnings)
    }

    fun report(title: String): String = buildString {
        appendLine("== $title: $files files")
        appendLine("names (11a): ${names.values.sumOf { it.total }} scored, $unboundNames not bound by Roslyn (answered anyway: $answeredUnbound)")
        table(names, exclude = setOf(DECLARATIONS))
        appendLine("types (11b): ${types.values.sumOf { it.total }} typed expressions")
        table(types, exclude = emptySet())
        appendLine("diagnostics (11e): errors $matchedErrors of $oracleErrors, warnings $matchedWarnings of $oracleWarnings, spurious $spuriousDiagnostics")
        val codes = (matchedByCode.keys + spuriousByCode.keys).toSortedSet()
        for (code in codes) appendLine("  %-8s Roslyn %6d  matched %6d  spurious %6d".format(code, oracleByCode[code] ?: 0, matchedByCode[code] ?: 0, spuriousByCode[code] ?: 0))
    }

    private fun StringBuilder.table(tallies: Map<String, Tally>, exclude: Set<String>) {
        appendLine("  %-30s %8s %8s %8s %10s %8s".format("category", "total", "correct", "wrong", "unresolved", "%"))
        for ((category, t) in tallies) if (t.total > 0) appendLine("  %-30s %8d %8d %8d %10d %8s".format(category, t.total, t.correct, t.wrong, t.unresolved, t.percent()))
        val all = Tally().also { sum -> tallies.filterKeys { it !in exclude }.values.forEach { sum.total += it.total; sum.correct += it.correct; sum.wrong += it.wrong; sum.unresolved += it.unresolved } }
        appendLine("  %-30s %8d %8d %8d %10d %8s".format(if (exclude.isEmpty()) "all" else "all but ${exclude.joinToString()}", all.total, all.correct, all.wrong, all.unresolved, all.percent()))
    }

    fun examples(): String = buildString {
        if (spuriousExamples.isNotEmpty()) {
            appendLine("-- spurious diagnostics")
            spuriousExamples.forEach { appendLine("  $it") }
        }
        if (missedExamples.isNotEmpty()) {
            appendLine("-- missed diagnostics (of the codes the resolver reports)")
            missedExamples.forEach { appendLine("  $it") }
        }
        for ((category, t) in names + types.mapKeys { "type: ${it.key}" }) {
            if (t.examples.isEmpty()) continue
            appendLine("-- $category")
            t.examples.forEach { appendLine("  $it") }
        }
    }

    companion object {
        const val DECLARATIONS = "declarations"

        /** The codes the native semantic checks report (task C4c): their misses are listed. */
        val NATIVE_CODES = setOf("CS0103", "CS0246", "CS0234", "CS1061", "CS0117", "CS1501", "CS7036", "CS1503", "CS0029", "CS0266", "CS0161", "CS0120", "CS8019", "CS8933",
            "CS0162", "CS0168", "CS0219", "CS4014", "CS8600", "CS8603", "CS8618", "CS8625")

        val NAME_CATEGORIES = listOf(
            "locals", "parameters", "local functions", "labels", "type parameters",
            "members of own type", "inherited members", "other members (using static ...)",
            "types: solution", "types: assemblies", "namespaces", "aliases",
            "member access: solution", "member access: assemblies",
            "var / keywords", "candidates", "other", DECLARATIONS,
        )

        val TYPE_CATEGORIES = listOf(
            "literals", "names", "member access", "invocations", "object creation", "this / base", "operators", "lambdas", "type syntax", "other",
        )

        private val MEMBER_KINDS = setOf("Property", "Field", "Event")

        /** The category of a name, null when Roslyn binds it to nothing (not scored). */
        fun nameCategory(name: Name): String? {
            if (name.symbols.isEmpty()) return null
            if (name.declares) return DECLARATIONS
            if (name.has("kw")) return "var / keywords"
            if (name.candidateReason != null) return "candidates"
            val symbol = name.symbols.first()
            val kind = symbol.kind
            val member = kind.startsWith("Method.") && kind != "Method.LocalFunction" || kind in MEMBER_KINDS
            return when {
                kind == "Local" || kind == "Local.Const" || kind == "RangeVariable" -> "locals"
                kind == "Parameter" -> "parameters"
                kind == "Method.LocalFunction" -> "local functions"
                kind == "Label" -> "labels"
                kind == "TypeParameter" -> "type parameters"
                kind == "Namespace" -> "namespaces"
                kind == "Alias" -> "aliases"
                member && name.has("acc") -> if (symbol.isFromAssembly) "member access: assemblies" else "member access: solution"
                member && name.has("own") -> "members of own type"
                member && name.has("inh") -> "inherited members"
                member -> "other members (using static ...)"
                kind.startsWith("NamedType.") -> if (symbol.isFromAssembly) "types: assemblies" else "types: solution"
                else -> "other"
            }
        }

        fun typeCategory(kind: String): String = when {
            kind.endsWith("LiteralExpression") || kind == "InterpolatedStringExpression" -> "literals"
            kind == "IdentifierName" || kind == "GenericName" -> "names"
            kind == "SimpleMemberAccessExpression" || kind == "PointerMemberAccessExpression" || kind == "MemberBindingExpression" || kind == "ConditionalAccessExpression" ||
                kind == "ElementAccessExpression" || kind == "ElementBindingExpression" -> "member access"
            kind == "InvocationExpression" -> "invocations"
            kind.endsWith("CreationExpression") || kind == "CollectionExpression" || kind == "AnonymousObjectCreationExpression" -> "object creation"
            kind == "ThisExpression" || kind == "BaseExpression" -> "this / base"
            kind.endsWith("LambdaExpression") || kind == "AnonymousMethodExpression" -> "lambdas"
            kind == "PredefinedType" || kind == "ArrayType" || kind == "NullableType" || kind == "PointerType" || kind == "TupleType" || kind == "QualifiedName" ||
                kind == "AliasQualifiedName" || kind == "FunctionPointerType" || kind == "RefType" || kind == "ScopedType" -> "type syntax"
            kind.endsWith("Expression") && (kind.startsWith("Add") || kind.startsWith("Subtract") || kind.startsWith("Multiply") || kind.startsWith("Divide") ||
                kind.startsWith("Modulo") || kind.startsWith("Logical") || kind.startsWith("Bitwise") || kind.startsWith("Equals") || kind.startsWith("NotEquals") ||
                kind.startsWith("Less") || kind.startsWith("Greater") || kind.startsWith("Coalesce") || kind.startsWith("Unary") || kind.startsWith("Pre") ||
                kind.startsWith("Post") || kind.endsWith("AssignmentExpression") || kind.startsWith("LeftShift") || kind.startsWith("RightShift") ||
                kind.startsWith("UnsignedRightShift") || kind.startsWith("ExclusiveOr") || kind == "ConditionalExpression" || kind == "IsExpression" ||
                kind == "AsExpression" || kind == "CastExpression" || kind == "IsPatternExpression") -> "operators"
            else -> "other"
        }

        /** Real documentation comment ids (`T:...`, `M:...`) identify a symbol; the `<Kind>:<name>` of locals does not. */
        private fun isDocId(id: String): Boolean = id.length > 2 && id[1] == ':' && id[0] in "NTMPFE"

        fun matches(answer: SemanticAnswer, symbols: List<SemanticDump.Symbol>): Boolean = symbols.any { symbol ->
            (answer.id != null && isDocId(answer.id) && answer.id == symbol.id) ||
                (answer.places.isNotEmpty() && symbol.declarations.containsAll(answer.places))
        }
    }
}
