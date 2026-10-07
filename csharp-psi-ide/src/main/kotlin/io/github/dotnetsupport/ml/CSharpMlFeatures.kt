package io.github.dotnetsupport.ml

import io.github.completionml.core.lex.CSharpLanguage
import io.github.completionml.core.rank.FeatureSchema
import io.github.completionml.core.rank.ProxyExampleGenerator
import io.github.completionml.core.spi.ContextKind
import io.github.completionml.core.spi.MlLanguage
import io.github.completionml.core.spi.MlToken
import kotlin.math.abs
import kotlin.math.ln

/**
 * The C# adapter of the shared ML completion engine (`../idea-ml-completion/docs/ADAPTER.md`, ML_RANKER_EXPORT_TASK.md): the lexer-only
 * language description of ml-core plus the language block of ranker features. Pure Kotlin: this module never sees the platform, the
 * plugin's completion attaches a [CSharpMlCandidate] to every lookup element it makes (`lang/NativeCSharpMlInfo`), the offline export
 * and the IDE ranker both compute the features through [CSharpMlFeatures] — the one code path that keeps training and serving identical.
 */
object CSharpMlLanguage : MlLanguage by CSharpLanguage {
    override val rankFeatures: List<String> get() = CSharpMlFeatures.NAMES
}

/** What the plugin's completion knows a candidate to be; the one-hot block of [CSharpMlFeatures.NAMES] is made of these. */
enum class CSharpMlCandidateKind { LOCAL, PARAMETER, LOCAL_FUNCTION, FIELD, PROPERTY, METHOD, EVENT, CONSTANT, TYPE, TYPE_PARAMETER, NAMESPACE, KEYWORD, OTHER }

/** How far from the caret a candidate is declared: the `scope_level` feature, smaller is nearer. */
object CSharpMlScope {
    /** Locals, parameters, local functions, type parameters, labels, keywords. */
    const val LOCAL = 0
    /** Members and nested types of the enclosing types. */
    const val MEMBER = 1
    /** Members of the base types (`base.`). */
    const val BASE_MEMBER = 2
    /** Members of a `using static` type, or of the receiver after a dot. */
    const val RECEIVER = 3
    /** Types of the solution and of the imported namespaces; the fallback of an item the plugin told nothing about. */
    const val IMPORTED = 4
    /** Not imported: choosing it adds a `using`. */
    const val UNIMPORTED = 5
}

/**
 * One candidate as the ranker sees it. Built by the plugin's completion for its items and read back by the offline export
 * (`CSharpMlDatasetExport`) and the IDE weigher; free of platform and ml-core types so the main module can carry it.
 *
 * @param expectedTypeMatch 0 none, 1 assignable, 2 identical (the plugin's rules only know "fits": 2)
 * @param declarationOffset offset of the declaration in the completed file; null when it is elsewhere or unknown
 * @param rulePriority the priority the plugin's rules gave (`PrioritizedLookupElement`): the rule order is a feature of its own
 */
class CSharpMlCandidate(
    val lookupString: String,
    val kind: CSharpMlCandidateKind,
    val isStatic: Boolean,
    val scopeLevel: Int,
    val needsUsing: Boolean,
    val expectedTypeMatch: Int,
    val declarationOffset: Int?,
    val rulePriority: Double,
) {
    override fun toString(): String = "$kind $lookupString scope=$scopeLevel static=$isStatic using=$needsUsing expected=$expectedTypeMatch decl=$declarationOffset rule=$rulePriority"
}

/** The language block of the C# ranker: names, order and the one function that computes it. Changing [NAMES] changes the schema hash: retrain. */
object CSharpMlFeatures {
    /** The language block, in order. Values are small floats: flags, levels, `ln(1 + x)` for distances and ranks. */
    val NAMES: List<String> = listOf(
        "kind_local",           // CSharpMlCandidateKind.LOCAL
        "kind_param",           // PARAMETER
        "kind_local_func",      // LOCAL_FUNCTION
        "kind_field",           // FIELD
        "kind_property",        // PROPERTY
        "kind_method",          // METHOD (extension methods too)
        "kind_event_const",     // EVENT, CONSTANT (enum members too)
        "kind_type",            // TYPE, TYPE_PARAMETER (predefined types too)
        "kind_namespace",       // NAMESPACE
        "kind_keyword",         // KEYWORD
        "is_static",            // a static member or a static class
        "scope_level",          // CSharpMlScope 0..5
        "needs_using",          // choosing the candidate adds a using directive
        "expected_type_match",  // 0 none, 1 assignable, 2 identical
        "list_has_expected",    // some candidate of the list matches an expected type (= an expected type is known)
        "declared_in_file",     // the declaration is in the completed file
        "decl_distance_log",    // ln(1 + |caret - declaration offset|) / 10 when declared_in_file, else 0
        "rule_rank_log",        // ln(1 + rank under the plugin's rules: priority desc, then name)
        "after_dot",            // the caret is after `.` / `?.` / `::` / `->`: a member list
    )

    val schema: FeatureSchema = FeatureSchema.common(languageFeatures = NAMES)

    /** The language block for every candidate of one list, in list order. */
    fun languageBlock(caretOffset: Int, afterDot: Boolean, candidates: List<CSharpMlCandidate>): Array<FloatArray> {
        val hasExpected = if (candidates.any { it.expectedTypeMatch > 0 }) 1f else 0f
        val ruleOrder = candidates.indices.sortedWith(compareBy({ -candidates[it].rulePriority }, { candidates[it].lookupString }))
        val ruleRank = IntArray(candidates.size).also { r -> ruleOrder.forEachIndexed { rank, c -> r[c] = rank } }
        val dot = if (afterDot) 1f else 0f
        return Array(candidates.size) { c ->
            val cand = candidates[c]
            val f = FloatArray(NAMES.size)
            when (cand.kind) {
                CSharpMlCandidateKind.LOCAL -> f[0] = 1f
                CSharpMlCandidateKind.PARAMETER -> f[1] = 1f
                CSharpMlCandidateKind.LOCAL_FUNCTION -> f[2] = 1f
                CSharpMlCandidateKind.FIELD -> f[3] = 1f
                CSharpMlCandidateKind.PROPERTY -> f[4] = 1f
                CSharpMlCandidateKind.METHOD -> f[5] = 1f
                CSharpMlCandidateKind.EVENT, CSharpMlCandidateKind.CONSTANT -> f[6] = 1f
                CSharpMlCandidateKind.TYPE, CSharpMlCandidateKind.TYPE_PARAMETER -> f[7] = 1f
                CSharpMlCandidateKind.NAMESPACE -> f[8] = 1f
                CSharpMlCandidateKind.KEYWORD -> f[9] = 1f
                CSharpMlCandidateKind.OTHER -> {}
            }
            f[10] = if (cand.isStatic) 1f else 0f
            f[11] = cand.scopeLevel.toFloat()
            f[12] = if (cand.needsUsing) 1f else 0f
            f[13] = cand.expectedTypeMatch.toFloat()
            f[14] = hasExpected
            val decl = cand.declarationOffset
            if (decl != null) { f[15] = 1f; f[16] = (ln(1.0 + abs(caretOffset - decl)) / 10).toFloat() }
            f[17] = ln(1.0 + ruleRank[c]).toFloat()
            f[18] = dot
            f
        }
    }

    /**
     * The order of the candidates of one list as both the export and the IDE ranker feed them to the feature extractor: the plugin's
     * rule order (priority descending, then the lookup string). The list-relative common features of the engine (`freq_rank_log`,
     * `lm_rank_log`) break their ties by the input order, so the order has to be the same in both paths — the lookup's own order is not
     * known before the weigher runs (0.1.132; the e18 shards used the lookup order, which is this one up to the prefix and statistics weighers).
     */
    fun ordered(candidates: List<CSharpMlCandidate>): List<CSharpMlCandidate> = candidates.sortedWith(compareBy({ -it.rulePriority }, { it.lookupString }))

    /** Index of [name] in the language block (tests and reports). */
    fun index(name: String): Int = NAMES.indexOf(name).also { require(it >= 0) { "no language feature '$name'" } }

    /** Context kind of the schema's one-hot block, from the lexer tokens before the caret (shared with the proxy generator). */
    fun contextKind(tokens: List<MlToken>, caretTokenIndex: Int): ContextKind =
        if (caretTokenIndex <= 0 || caretTokenIndex > tokens.size) ContextKind.OTHER else ProxyExampleGenerator.contextKind(tokens, caretTokenIndex)

    /** Whether the token before the caret makes the list a member list. */
    fun isAfterDot(tokens: List<MlToken>, caretTokenIndex: Int): Boolean =
        caretTokenIndex > 0 && caretTokenIndex <= tokens.size && tokens[caretTokenIndex - 1].text in DOTS

    private val DOTS = setOf(".", "?.", "::", "->")
}
