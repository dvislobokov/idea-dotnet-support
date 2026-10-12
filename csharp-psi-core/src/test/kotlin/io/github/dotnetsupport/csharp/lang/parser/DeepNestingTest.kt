package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode

/**
 * Depth behaviour of the slice parser (see [SyntaxParser.MAX_DEPTH]).
 *  - Legal deep chains below the weighted limit parse without an error (Roslyn has no artificial depth limit, only a
 *    stack guard, LP 11500; the limit here stands in for it and must not change the parse of realistic code).
 *  - Nesting far above the limit does not overflow the stack of the test JVM's default thread: the guard fires first.
 *  - A real overflow (a thread with a tiny stack) degrades to Roslyn's behaviour through
 *    [SyntaxParser.parseWithStackGuard]: the whole text is one error element, no exception.
 *  - Nested parenthesized patterns are not exponential: the reparse decision is memoised per position
 *    (`LanguageParser.parenPatternDecisions`); depth 40 and depth 120 must finish in bounded time.
 *  - The parse-then-reparse decisions of switch statements (governing expression, case label) are memoised the same
 *    way (`switchHeaderDecisions`, `caseLabelDecisions`): nested switch statements in lambdas stay polynomial.
 *  - So is the local-function-or-expression decision of `unsafe (` (`unsafeLocalFunctionDecisions`).
 *  - [testReportStackHeadroom] prints, for information, the depth each construct reaches on this thread before the
 *    guard stops it (and whether the stack would have stopped it earlier).
 */
class DeepNestingTest : CSharpParsingTestCase("parser/slice") {

    private fun parseOk(text: String, statement: Boolean = false): SliceParseHarness.Result {
        val r = try {
            SliceParseHarness.parse(text, statement)
        } catch (e: StackOverflowError) {
            throw AssertionError("stack overflow outside the parser (tree mapping) in: ${text.take(80)}...")
        }
        assertFalse("stack overflow in: ${text.take(80)}...", r.stackOverflow)
        assertEquals("errors in: ${text.take(80)}...", 0, r.errorCount)
        assertEquals("error elements in: ${text.take(80)}...", 0, r.errorElements)
        return r
    }

    fun testLegalDeepChainsParseWithoutErrors() {
        // one depth unit per level
        parseOk("a ? b : ".repeat(450) + "z")
        parseOk("a ?? ".repeat(450) + "z")
        parseOk("a = ".repeat(450) + "z")
        parseOk("!".repeat(550) + "a")
        parseOk("-".repeat(550) + "a")
        parseOk("{ ".repeat(500) + "}".repeat(500), statement = true)
        parseOk("if (a) ".repeat(500) + ";", statement = true)
        parseOk("while (a) ".repeat(500) + ";", statement = true)
        // `else if` chains are iterative in Roslyn (ParseIfStatement, LP 10077) and here: no depth unit per link
        parseOk("if (a) ; else ".repeat(700) + ";", statement = true)
        // two units per level
        parseOk("(".repeat(250) + "a" + ")".repeat(250))
        parseOk("() => ".repeat(250) + "1")
        // four units per pattern level
        parseOk("x is " + "(".repeat(120) + "1" + ")".repeat(120))
        parseOk("x is " + "(".repeat(120) + "not null" + ")".repeat(120))
        // iteration in the parser (the tree is as deep as the chain, the test mapping recurses over it)
        parseOk("a" + ".b()".repeat(1000))
        parseOk("a" + " + a".repeat(1500))
    }

    fun testParenthesizedPatternsAreNotExponential() {
        for (depth in listOf(20, 40, 120)) {
            val t0 = System.nanoTime()
            parseOk("x is " + "(".repeat(depth) + "1" + ")".repeat(depth))
            parseOk("x is " + "(".repeat(depth) + "not null" + ")".repeat(depth))
            val millis = (System.nanoTime() - t0) / 1_000_000
            println("  parenthesized patterns depth $depth: $millis ms")
            assertTrue("depth $depth took $millis ms", millis < 3000)
        }
    }

    private fun nested(depth: Int, wrap: (String) -> String): String {
        var s = "x;"
        repeat(depth) { s = wrap(s) }
        return s
    }

    /** `parseSwitchHeader` reparses `(e)` without the wrapper (LP 10263); unmemoised, depth 18 took 1.4 s. */
    fun testSwitchHeaderReparseIsNotExponential() {
        for (depth in listOf(12, 24, 60)) {
            val t0 = System.nanoTime()
            parseOk(nested(depth) { "switch ((() => { $it })) { }" }, statement = true)
            val millis = (System.nanoTime() - t0) / 1_000_000
            println("  switch headers depth $depth: $millis ms")
            assertTrue("depth $depth took $millis ms", millis < 3000)
        }
    }

    /** `parseExpressionOrPatternForSwitchStatement` reparses a constant as an expression (LPP 459); was 1.9 s at 18. */
    fun testCaseLabelReparseIsNotExponential() {
        for (depth in listOf(12, 24, 60)) {
            val t0 = System.nanoTime()
            parseOk(nested(depth) { "switch (a) { case 1 + F(() => { $it }): break; }" }, statement = true)
            parseOk(nested(depth) { "switch (a) { case 1: case 1 + F(() => { $it }) when b: break; }" }, statement = true)
            val millis = (System.nanoTime() - t0) / 1_000_000
            println("  case labels depth $depth: $millis ms")
            assertTrue("depth $depth took $millis ms", millis < 3000)
        }
    }

    /**
     * `unsafe (` decides between a local function and an expression by parsing the local function, body included
     * (LP 8541); the decision is memoised (`unsafeLocalFunctionDecisions`). Unmemoised, depth 18 took 0.8 s, depth 24 23 s.
     */
    fun testUnsafeLocalFunctionDecisionIsNotExponential() {
        for (depth in listOf(12, 24, 60)) {
            val t0 = System.nanoTime()
            val r = parseOk("{ " + "unsafe (int, int) F() { ".repeat(depth) + "}".repeat(depth) + " }", statement = true)
            val millis = (System.nanoTime() - t0) / 1_000_000
            println("  unsafe local functions depth $depth: $millis ms")
            assertTrue("depth $depth took $millis ms", millis < 1000)
            fun count(n: DumpNode): Int = (if (n.kind == "LocalFunctionStatement") 1 else 0) + n.children.sumOf { count(it) }
            assertEquals(depth, r.roots.sumOf { count(it) })
        }
    }

    /** A long `into` chain recurses through `parseQueryBody`: legal lengths parse, very long ones hit the guard. */
    fun testQueryContinuationChains() {
        parseOk("from a in b select a" + " into a select a".repeat(300))
        val r = SliceParseHarness.parse("from a in b select a" + " into a select a".repeat(5000), statement = false)
        assertFalse("the guard must fire before the stack ends", r.stackOverflow)
        assertTrue(r.errorCount > 0)
    }

    /** The statement depth guard skips to the `;` but not past the `}` of an enclosing block. */
    fun testStatementGuardStopsAtEnclosingBrace() {
        val text = "{ { " + "if (a) ".repeat(SyntaxParser.MAX_DEPTH + 10) + "b } c(); }"
        val r = SliceParseHarness.parse(text, statement = true)
        assertFalse(r.stackOverflow)
        assertTrue(r.errorCount > 0)
        val c = text.indexOf("c();")
        val found = ArrayList<DumpNode>()
        fun walk(n: DumpNode) {
            if (n.kind == "ExpressionStatement" && n.start == c) found += n
            n.children.forEach { walk(it) }
        }
        r.roots.forEach { walk(it) }
        assertEquals("ExpressionStatement for `c();` after the guarded block", 1, found.size)
        val block = r.roots.first()
        assertEquals("Block", block.kind)
        assertEquals(text.length, block.end)
    }

    fun testNestingAboveTheLimitDoesNotOverflow() {
        val depth = SyntaxParser.MAX_DEPTH * 3
        for (text in listOf(
            "(".repeat(depth) + "a" + ")".repeat(depth),
            "x is " + "(".repeat(depth) + "1" + ")".repeat(depth),
            "() => ".repeat(depth) + "1",
            "!".repeat(depth) + "a",
            "a ? b : ".repeat(depth) + "z",
            "{ ".repeat(depth) + "}".repeat(depth),
            "if (a) ".repeat(depth) + ";",
        )) {
            val r = SliceParseHarness.parse(text, statement = text.startsWith("{") || text.startsWith("if"))
            assertFalse("the guard must fire before the stack ends: ${text.take(40)}", r.stackOverflow)
            assertTrue(r.errorCount > 0)
        }
    }

    fun testRealOverflowDegradesLikeRoslyn() {
        var result: SliceParseHarness.Result? = null
        var failure: Throwable? = null
        val text = "(".repeat(SyntaxParser.MAX_DEPTH / 2) + "a" + ")".repeat(SyntaxParser.MAX_DEPTH / 2)
        val thread = Thread(null, {
            try {
                result = SliceParseHarness.parse(text, statement = false)
            } catch (t: Throwable) {
                failure = t
            }
        }, "tiny-stack", 96 * 1024)
        thread.start()
        thread.join()
        assertNull(failure?.toString(), failure)
        val r = result!!
        assertTrue("expected the stack guard to fire on a 96 KB stack", r.stackOverflow)
        assertEquals(1, r.errorElements)
        assertEquals(1, r.errorCount)
        assertEquals(text.length, r.root.textLength)
    }

    /** Informational: the nesting depth each construct reaches on this thread before the guard or the stack stops it. */
    fun testReportStackHeadroom() {
        for ((name, make) in listOf<Pair<String, (Int) -> String>>(
            "parens" to { d -> "(".repeat(d) + "a" + ")".repeat(d) },
            "patterns" to { d -> "x is " + "(".repeat(d) + "1" + ")".repeat(d) },
            "lambdas" to { d -> "() => ".repeat(d) + "1" },
            "ternaries" to { d -> "a ? b : ".repeat(d) + "z" },
            "blocks" to { d -> "{ ".repeat(d) + "}".repeat(d) },
            "ifs" to { d -> "if (a) ".repeat(d) + ";" },
        )) {
            var ok = 0
            var depth = 50
            while (depth <= 4000) {
                val r = SliceParseHarness.parse(make(depth), statement = name == "blocks" || name == "ifs")
                if (r.stackOverflow) {
                    println("  $name: stack overflow at depth $depth (last ok $ok)")
                    break
                }
                if (r.errorCount > 0) {
                    println("  $name: guard at depth $depth (last ok $ok)")
                    break
                }
                ok = depth
                depth += 50
            }
        }
    }
}
