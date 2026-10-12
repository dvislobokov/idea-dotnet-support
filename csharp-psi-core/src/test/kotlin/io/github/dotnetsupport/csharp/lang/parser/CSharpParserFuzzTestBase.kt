package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.impl.BlockSupportImpl
import com.intellij.psi.impl.ChangedPsiRangeUtil
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.FileElement
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode
import kotlin.random.Random

/**
 * Engine of the mutation fuzz gates: [CSharpParserFuzzTest] (fast, in `test`) and [CSharpParserFuzzCorpusTest]
 * (corpus sample, in `corpusTest`). Adapted from go-psi's `GoParserFuzzTestBase`.
 *
 * Every source is mutated [Kind]-wise with a seeded [Random] (the seed of a source depends only on the base seed and
 * its name, so a finding is reproducible from the printed `name / kind@offset`): delete a token, duplicate a token,
 * insert a token from [insertions] before a token, swap two adjacent tokens, truncate at a random offset, delete a
 * random line. Tokens are the lexer's, without whitespace, comments and directive lines.
 *
 * Hard rules for the original and every mutant (collected as [Finding]s, the test fails on any):
 *  - the parse does not throw, overflow the stack, time out or log an error (PsiBuilder's "Unbalanced tree", "Another
 *    not done marker", ...), see [ParseGuard];
 *  - the tree covers the whole text losslessly and its leaves are a coarsening of the lexer's tokens
 *    ([ParserGateSupport.checkTokenCoverage]: nothing lost, nothing split);
 *  - the parse takes less than [SLOW_PARSE_MILLIS];
 *  - **incremental equals full** (every mutant): the platform's incremental reparse of the original file to the
 *    mutant's text (`ChangedPsiRangeUtil` + `BlockSupportImpl.findReparseableNodeAndReparseIt`, what a document commit
 *    runs) either falls back to a full parse, or reparses one body alone ([CSharpBodyBlockType]); then the original
 *    tree with that body replaced must equal the full parse of the mutant element by element, element types included
 *    ([BodyReparseSupport.firstMismatch]). Counted: `bodyReparses` (handled by a body reparse, higher is better) and
 *    `fullReparses` (fell back).
 *
 * Recovery locality of the full parse (`localityViolations`, informational since bodies are reparsed alone: what the
 * editor sees is decided by the incremental check above): a mutation is *local* when it is a single-token
 * kind, lies strictly inside the body `Block` of a member (`MethodDeclaration`, constructor, destructor, operator,
 * conversion operator, accessor: [FuzzLocality.memberKinds]; the innermost such node of the original tree) and leaves that body
 * lexically self-contained: in the mutant's tokens the body still starts with its `{`, ends with a `}` exactly at the
 * shifted end, and the braces in between are balanced without closing the body early (the condition under which a lazy
 * reparseable body can be reparsed alone; a mutation that opens a comment or a string running past the body is not
 * local). For a local mutation the tree outside the member must not change: the preorder list of (depth, kind, span) of
 * all nodes and tokens of the [PsiToDump][ParserGateSupport.newDump] tree, the member's descendants excluded and
 * offsets after the mutation shifted by the length delta, must be equal for the original and the mutant, and the
 * mutant must have a node of the member's kind at the same depth and start. Otherwise it is a violation. With the
 * placeholder parser (no nodes) nothing is checked.
 */
abstract class CSharpParserFuzzTestBase : CSharpParsingTestCase("parser") {

    enum class Kind(val isSingleToken: Boolean) {
        DELETE_TOKEN(true),
        DUPLICATE_TOKEN(true),
        INSERT_TOKEN(true),
        SWAP_TOKENS(true),
        TRUNCATE(false),
        DELETE_LINE(false),
    }

    /** A mutant: [start, end) is the changed range of the original text (of [originalLength]), [text] the mutated text. */
    class Mutant(val kind: Kind, val start: Int, val end: Int, val text: String, val originalLength: Int) {
        val delta get() = text.length - originalLength
    }

    class Finding(val source: String, val kind: String, val offset: Int, val message: String) {
        override fun toString() = "$source  $kind@$offset  $message"
    }

    class Result {
        var sources = 0
        var sourcesWithNodes = 0
        var mutants = 0
        var maxParseMillis = 0L
        var slowest = ""
        /** Per kind: mutants, locality checked, locality violations. */
        val perKind = LinkedHashMap<Kind, IntArray>().apply { Kind.entries.forEach { put(it, IntArray(3)) } }
        var bodyReparses = 0
        var fullReparses = 0
        val failures = ArrayList<Finding>()
        val violations = ArrayList<Finding>()
        val localityChecked get() = perKind.values.sumOf { it[1] }
    }

    private val insertions = listOf(
        "(", ")", "{", "}", "[", "]", ";", ",", ".", "=", "=>", "<", ">", "?", ":", "?.", "\"", "/*",
        "class", "struct", "interface", "namespace", "using", "public", "static", "async", "await", "new", "var", "if",
        "else", "for", "foreach", "while", "do", "switch", "case", "return", "yield", "throw", "try", "catch", "in", "is",
        "out", "ref", "where", "select", "from", "get", "set", "operator", "delegate", "#if X", "\$\"",
    )

    private class Parsed(val roots: List<DumpNode>, val node: ASTNode)

    /** Runs [mutationsPerSource] mutations on every `(name, text)` source. */
    protected fun fuzz(sources: List<Pair<String, String>>, mutationsPerSource: Int, seed: Long): Result {
        val result = Result()
        ParseGuard.interceptLoggedErrors {
            for ((name, text) in sources) {
                val original = parse(name, "ORIGINAL", 0, text, result) ?: continue
                result.sources++
                if (ParserGateSupport.hasNodes(original.roots)) result.sourcesWithNodes++
                val tokens = significantTokens(text)
                if (tokens.isEmpty()) continue
                val bodies = FuzzLocality.bodies(original.roots)
                val random = Random(seed * 31 + name.hashCode())
                repeat(mutationsPerSource) { index ->
                    val kind = Kind.entries[(index + result.sources) % Kind.entries.size]
                    val mutant = mutate(text, tokens, kind, random)
                    result.mutants++
                    val stats = result.perKind.getValue(kind)
                    stats[0]++
                    val parsed = parse(name, kind.name, mutant.start, mutant.text, result) ?: return@repeat
                    checkIncremental(name, kind.name, original, mutant, parsed, result)
                    val body = if (kind.isSingleToken) FuzzLocality.localBody(bodies, mutant.start, mutant.end, mutant.text, mutant.delta) else null
                    if (body != null) {
                        stats[1]++
                        val violation = FuzzLocality.check(original.roots, parsed.roots, body, mutant.end, mutant.delta)
                        if (violation != null) {
                            stats[2]++
                            result.violations += Finding(name, kind.name, mutant.start, "$violation  near: ${snippet(mutant)}")
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parse(name: String, kind: String, offset: Int, text: String, result: Result): Parsed? {
        val mapper = ParserGateSupport.newDump()
        val started = System.nanoTime()
        val outcome = ParseGuard.run(PARSE_TIMEOUT_MILLIS) {
            val node = createFile(name.substringAfterLast('/'), text).node
            val coverage = ParserGateSupport.checkTokenCoverage(node, text)
            Triple(mapper.map(node), coverage, node)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        if (millis > result.maxParseMillis) {
            result.maxParseMillis = millis
            result.slowest = "$name $kind@$offset ($millis ms, ${text.length} chars)"
        }
        fun fail(message: String) {
            result.failures += Finding(name, kind, offset, message)
        }
        outcome.loggedErrors.forEach { fail("logged error: $it") }
        val value = outcome.value
        if (value == null) {
            fail(outcome.describe() + (outcome.failure?.stackTrace?.take(6)?.joinToString("") { "\n      at $it" } ?: ""))
            return null
        }
        value.second?.let { fail("coverage: $it") }
        if (millis >= SLOW_PARSE_MILLIS) fail("parse took $millis ms")
        return Parsed(value.first, value.third)
    }

    /** The incremental-equals-full rule (class documentation) for [mutant] of [original]; [parsed] is its full parse. */
    private fun checkIncremental(name: String, kind: String, original: Parsed, mutant: Mutant, parsed: Parsed, result: Result) {
        val fileNode = original.node as FileElement
        val file = fileNode.psi as PsiFileImpl
        try {
            val range = ChangedPsiRangeUtil.getChangedPsiRange(file, fileNode, mutant.text) ?: return
            val reparsed = BlockSupportImpl.findReparseableNodeAndReparseIt(file, fileNode, range, mutant.text)
            val oldNode = reparsed?.first
            val newNode = reparsed?.second
            if (oldNode == null || newNode == null) {
                result.fullReparses++
                return
            }
            result.bodyReparses++
            BodyReparseSupport.firstMismatch(parsed.node, fileNode, oldNode, newNode)?.let {
                result.failures += Finding(name, kind, mutant.start, "body reparse differs from the full parse $it  near: ${snippet(mutant)}")
            }
        } catch (e: Throwable) {
            result.failures += Finding(name, kind, mutant.start, "incremental reparse failed: $e")
        }
    }

    protected fun printSummary(title: String, result: Result, worst: Int = 10) {
        println("$title summary")
        println("  sources:            ${result.sources} (with syntax nodes: ${result.sourcesWithNodes})")
        println("  mutants:            ${result.mutants}")
        println("  hard failures:      ${result.failures.size}")
        println("  bodyReparses:       ${result.bodyReparses} (full reparses: ${result.fullReparses})")
        println("  locality checked:   ${result.localityChecked}")
        println("  localityViolations: ${result.violations.size}")
        println("  max parse time:     ${result.maxParseMillis} ms (${result.slowest})")
        println("  by kind (mutants / locality checked / violations):")
        result.perKind.forEach { (kind, s) -> println("    %-16s %7d %7d %7d".format(kind, s[0], s[1], s[2])) }
        result.failures.take(worst).forEach { println("  FAILURE $it") }
        result.violations.take(worst).forEach { println("  LOCALITY $it") }
    }

    protected fun assertNoHardFailures(result: Result) {
        assertTrue(
            "${result.failures.size} fuzz failures, first:\n" + result.failures.take(10).joinToString("\n"),
            result.failures.isEmpty(),
        )
    }

    private fun snippet(m: Mutant): String =
        m.text.substring((m.start - 30).coerceAtLeast(0), (m.start + 40).coerceAtMost(m.text.length)).replace("\n", "\\n")

    // --- mutations -------------------------------------------------------------------------------

    private fun significantTokens(text: String): List<IntRange> {
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<IntRange>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type !in CSharpTokenTypes.WHITESPACES && type !in CSharpTokenTypes.COMMENTS && lexer.tokenEnd > lexer.tokenStart) {
                tokens += lexer.tokenStart until lexer.tokenEnd
            }
            lexer.advance()
        }
        return tokens
    }

    private fun mutate(text: String, tokens: List<IntRange>, kind: Kind, random: Random): Mutant {
        val i = random.nextInt(tokens.size)
        val token = tokens[i]
        val n = text.length
        return when (kind) {
            Kind.DELETE_TOKEN -> Mutant(kind, token.first, token.last + 1, text.removeRange(token), n)
            Kind.DUPLICATE_TOKEN ->
                Mutant(kind, token.last + 1, token.last + 1, text.substring(0, token.last + 1) + " " + text.substring(token) + text.substring(token.last + 1), n)
            Kind.INSERT_TOKEN ->
                Mutant(kind, token.first, token.first, text.substring(0, token.first) + insertions[random.nextInt(insertions.size)] + " " + text.substring(token.first), n)
            Kind.SWAP_TOKENS -> {
                if (i + 1 >= tokens.size) {
                    Mutant(kind, token.first, token.first, text, n)
                } else {
                    val next = tokens[i + 1]
                    Mutant(
                        kind, token.first, next.last + 1,
                        text.substring(0, token.first) + text.substring(next) + text.substring(token.last + 1, next.first) +
                            text.substring(token) + text.substring(next.last + 1),
                        n,
                    )
                }
            }
            Kind.TRUNCATE -> {
                val offset = random.nextInt(text.length + 1)
                Mutant(kind, offset, text.length, text.substring(0, offset), n)
            }
            Kind.DELETE_LINE -> {
                val offset = random.nextInt(text.length + 1)
                val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
                val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }
                Mutant(kind, start, end, text.removeRange(start, end), n)
            }
        }
    }

    companion object {
        const val PARSE_TIMEOUT_MILLIS = 20_000L
        const val SLOW_PARSE_MILLIS = 5_000L
    }
}

/** Recovery locality of [CSharpParserFuzzTestBase] (the definition is in its documentation); separate for unit tests. */
object FuzzLocality {
    /** Kinds whose `Block` child is a member body: the unit a lazy reparseable body would cover. */
    val memberKinds = setOf(
        "MethodDeclaration", "ConstructorDeclaration", "DestructorDeclaration", "OperatorDeclaration",
        "ConversionOperatorDeclaration", "GetAccessorDeclaration", "SetAccessorDeclaration", "InitAccessorDeclaration",
        "AddAccessorDeclaration", "RemoveAccessorDeclaration", "UnknownAccessorDeclaration",
    )

    /** A member [member] at [depth] of the dump tree with its body [block]. */
    class Body(val member: DumpNode, val depth: Int, val block: DumpNode)

    fun bodies(roots: List<DumpNode>): List<Body> = ArrayList<Body>().also { collect(roots, 0, it) }

    private fun collect(nodes: List<DumpNode>, depth: Int, out: MutableList<Body>) {
        for (n in nodes) {
            if (n.isToken) continue
            if (n.kind in memberKinds) {
                n.children.firstOrNull { !it.isToken && it.kind == "Block" }?.let { out += Body(n, depth, it) }
            }
            collect(n.children, depth + 1, out)
        }
    }

    /**
     * The innermost body strictly containing the original range [start, end) of a mutation whose mutated [text] (length
     * delta [delta]) keeps that body lexically self-contained ([selfContained]), or null.
     */
    fun localBody(bodies: List<Body>, start: Int, end: Int, text: String, delta: Int): Body? {
        val body = bodies.filter { start > it.block.start && end < it.block.end }
            .minByOrNull { it.block.end - it.block.start } ?: return null
        return if (selfContained(text, body.block.start, body.block.end + delta)) body else null
    }

    /** In the lexer's tokens of [text], [start, end) is `{` ... `}` with balanced braces that close only at the end. */
    fun selfContained(text: String, start: Int, end: Int): Boolean {
        if (end > text.length || start >= end) return false
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        var depth = 0
        var opened = false
        while (true) {
            val type = lexer.tokenType ?: return false
            val s = lexer.tokenStart
            val e = lexer.tokenEnd
            if (e > start && s < start) return false // a token straddles the body's start
            if (s >= start) {
                if (s >= end || e > end) return false
                if (!opened) {
                    if (type != SyntaxKind.OpenBraceToken || s != start) return false
                    opened = true
                }
                when (type) {
                    SyntaxKind.OpenBraceToken -> depth++
                    SyntaxKind.CloseBraceToken -> {
                        depth--
                        if (depth == 0) return e == end
                    }
                }
            }
            lexer.advance()
        }
    }

    /**
     * The violation of locality, or null: [original] and [mutated] differ outside [body]'s member (offsets of the
     * original at or after [mutationEnd] shifted by [delta]), or the mutant has no node of the member's kind at the
     * member's depth and start.
     */
    fun check(original: List<DumpNode>, mutated: List<DumpNode>, body: Body, mutationEnd: Int, delta: Int): String? {
        val before = ArrayList<String>()
        flatten(original, 0, body.member, { p -> if (p >= mutationEnd) p + delta else p }, before)
        val target = findNode(mutated, 0, body.depth, body.member.kind, body.member.start)
            ?: return "no ${body.member.kind} at ${body.member.start} in the mutant"
        val after = ArrayList<String>()
        flatten(mutated, 0, target, { it }, after)
        if (before == after) return null
        val i = before.indices.firstOrNull { it >= after.size || before[it] != after[it] } ?: before.size
        return "tree outside ${body.member.kind}@${body.member.start} changed: expected `${before.getOrNull(i)}`, got `${after.getOrNull(i)}`"
    }

    private fun flatten(nodes: List<DumpNode>, depth: Int, skip: DumpNode, shift: (Int) -> Int, out: MutableList<String>) {
        for (n in nodes) {
            out += "$depth ${if (n.isToken) "T" else "N"} ${n.kind} ${shift(n.start)} ${shift(n.end)}"
            if (n !== skip) flatten(n.children, depth + 1, skip, shift, out)
        }
    }

    private fun findNode(nodes: List<DumpNode>, depth: Int, targetDepth: Int, kind: String, start: Int): DumpNode? {
        for (n in nodes) {
            if (n.start > start) return null
            if (depth == targetDepth) {
                if (!n.isToken && n.kind == kind && n.start == start) return n
            } else if (!n.isToken && n.end >= start) {
                findNode(n.children, depth + 1, targetDepth, kind, start)?.let { return it }
            }
        }
        return null
    }
}
