package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.testFramework.LoggedErrorProcessor
import io.github.dotnetsupport.csharp.lang.CSharpElementType
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode
import io.github.dotnetsupport.csharp.lang.oracle.PsiToDump
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards one parse of a gate (tree gates, fuzz, benchmarks): exceptions, `StackOverflowError`, timeouts and errors the
 * platform *logs* instead of throwing (PsiBuilder's "Unbalanced tree", "Another not done marker", ...) become an
 * [Outcome] instead of failing or hanging the run.
 *
 * - The body runs on a daemon thread of a cached pool with the JVM's default stack size (as the IDE's pooled threads),
 *   under a [ProgressManager.runProcess] indicator that a watchdog cancels after `timeoutMillis`: PsiBuilder checks
 *   cancellation while it advances, so a slow parse ends with [ProcessCanceledException] ([Kind.TIMEOUT]).
 * - A parse that ignores the cancellation (a loop that never advances the builder) is abandoned after twice the timeout:
 *   [Kind.HUNG]; its thread keeps running as a daemon and is reported.
 * - Logged errors are collected while [interceptLoggedErrors] is active: the platform's test logger would otherwise
 *   fail the whole test at its end, without saying which file caused it.
 */
object ParseGuard {
    enum class Kind { EXCEPTION, STACK_OVERFLOW, TIMEOUT, HUNG }

    class Outcome<T>(val value: T?, val kind: Kind?, val failure: Throwable?, val loggedErrors: List<String>) {
        val ok get() = kind == null
        fun describe(): String = when (kind) {
            null -> "ok"
            Kind.HUNG -> "hung (did not stop after cancellation)"
            Kind.TIMEOUT -> "timeout"
            else -> failure.toString()
        }
    }

    private val sink = ThreadLocal<MutableList<String>?>()

    private val processor = object : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
            val errors = sink.get() ?: return super.processError(category, message, details, t)
            synchronized(errors) { errors += "$category: $message" + (t?.let { " ($it)" } ?: "") }
            return Action.NONE
        }
    }

    /** Runs [block] with logged errors of guarded parses collected into their [Outcome]s (process-wide, not nested). */
    fun <T> interceptLoggedErrors(block: () -> T): T = LoggedErrorProcessor.executeWith(processor).use { block() }

    private val threads = AtomicInteger()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "csharp-psi-parse-guard-${threads.incrementAndGet()}").apply { isDaemon = true }
    })
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "csharp-psi-parse-watchdog").apply { isDaemon = true } }

    fun <T> run(timeoutMillis: Long, body: () -> T): Outcome<T> {
        val errors = ArrayList<String>()
        val indicator = EmptyProgressIndicator()
        val future = pool.submit<Outcome<T>> {
            sink.set(errors)
            val cancel = watchdog.schedule({ indicator.cancel() }, timeoutMillis, TimeUnit.MILLISECONDS)
            try {
                var result: T? = null
                ProgressManager.getInstance().runProcess({ result = body() }, indicator)
                Outcome(result, null, null, errors)
            } catch (e: ProcessCanceledException) {
                if (indicator.isCanceled) Outcome(null, Kind.TIMEOUT, e, errors) else Outcome(null, Kind.EXCEPTION, e, errors)
            } catch (e: StackOverflowError) {
                Outcome(null, Kind.STACK_OVERFLOW, e, errors)
            } catch (e: Throwable) {
                Outcome(null, Kind.EXCEPTION, e, errors)
            } finally {
                cancel.cancel(false)
                sink.remove()
            }
        }
        return try {
            future.get(2 * timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            indicator.cancel()
            Outcome(null, Kind.HUNG, e, synchronized(errors) { errors.toList() })
        } catch (e: ExecutionException) {
            Outcome(null, Kind.EXCEPTION, e.cause ?: e, synchronized(errors) { errors.toList() })
        }
    }
}

/** What the gates share about our parser's output. */
object ParserGateSupport {
    /** The mapping every gate uses: our element types are nodes, the rest as in [PsiToDump]. */
    fun newDump() = PsiToDump(isNode = ::isSyntaxNode)

    /** Our node types: [CSharpElementType]s and the reparseable bodies ([CSharpBodyBlockType], Roslyn's `Block`). */
    fun isSyntaxNode(type: com.intellij.psi.tree.IElementType): Boolean = type is CSharpElementType || type is CSharpBodyBlockType

    /**
     * True when the tree has at least one syntax node: the placeholder parser of `CSharpParserDefinition` builds a flat
     * file of tokens, so a gate over it measures nothing and must not write baselines (docs/csharp-psi/TESTING.md).
     */
    fun hasNodes(roots: List<DumpNode>): Boolean = roots.any { !it.isToken }

    /**
     * Checks that the AST is a lossless coarsening of the lexer's tokens: the leaves spell [text] and every non-empty
     * leaf starts and ends at a token boundary of [CSharpLexer] (merged `>>` or a remapped keyword is fine, a split or
     * a lost token is not). A lazy-parseable composite whose range is one lexer token counts as that token (its inner
     * structure may be finer). Returns a description of the first violation or null.
     */
    fun checkTokenCoverage(root: ASTNode, text: String): String? {
        if (root.textLength != text.length) return "tree text length ${root.textLength} != ${text.length}"
        val boundaries = java.util.BitSet(text.length + 1)
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        boundaries.set(0)
        while (lexer.tokenType != null) {
            boundaries.set(lexer.tokenStart)
            boundaries.set(lexer.tokenEnd)
            lexer.advance()
        }
        boundaries.set(text.length)
        var offset = 0
        var failure: String? = null
        fun visit(n: ASTNode) {
            if (failure != null) return
            val type = n.elementType
            val atomic = n.firstChildNode == null ||
                (type is ILazyParseableElementType && boundaries[n.startOffset] && boundaries[n.startOffset + n.textLength] &&
                    tokensBetween(text, n.startOffset, n.startOffset + n.textLength) == 1)
            if (atomic) {
                val len = n.textLength
                if (len == 0) return
                if (n.startOffset != offset) {
                    failure = "leaf $type at ${n.startOffset}, expected $offset"
                    return
                }
                if (!boundaries[offset] || !boundaries[offset + len]) {
                    failure = "leaf $type ${offset}-${offset + len} does not align with the lexer's tokens"
                    return
                }
                if (!text.regionMatches(offset, n.chars, 0, len)) {
                    failure = "leaf $type at $offset has text `${n.text.take(40)}`, the source has `${text.substring(offset, offset + len).take(40)}`"
                    return
                }
                offset += len
                return
            }
            var c = n.firstChildNode
            while (c != null) {
                visit(c)
                c = c.treeNext
            }
        }
        visit(root)
        if (failure == null && offset != text.length) failure = "leaves cover $offset of ${text.length} characters"
        return failure
    }

    private fun tokensBetween(text: String, start: Int, end: Int): Int {
        val lexer = CSharpLexer()
        lexer.start(text, start, end, 0)
        var n = 0
        while (lexer.tokenType != null) {
            n++
            lexer.advance()
        }
        return n
    }
}

private fun String.regionMatches(offset: Int, other: CharSequence, otherOffset: Int, length: Int): Boolean {
    for (i in 0 until length) if (this[offset + i] != other[otherOffset + i]) return false
    return true
}
