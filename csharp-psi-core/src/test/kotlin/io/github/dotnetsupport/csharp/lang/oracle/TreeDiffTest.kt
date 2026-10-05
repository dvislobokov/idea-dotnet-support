package io.github.dotnetsupport.csharp.lang.oracle

import junit.framework.TestCase

/**
 * [TreeDiff] on the canned dump (`testData/oracle/snippets.tree`): a tree against itself, and against mutated copies
 * where every mutation must produce exactly one mismatch of the expected class (no cascades).
 */
class TreeDiffTest : TestCase() {

    private val files by lazy { RoslynDump.read(RoslynDumpFormatTest.cannedDump()) }
    private val directives get() = files[0]
    private val recovery get() = files[1]

    fun testSameTreeHasNoMismatches() {
        for (file in files) {
            val diff = TreeDiff()
            assertEquals(emptyList<Mismatch>(), diff.diff(file, file.roots))
            assertTrue(diff.expectedNodes > 0 && diff.expectedTokens > 0)
        }
    }

    fun testMissingAndZeroWidthTokensAreIgnored() {
        // Our tree cannot have missing (Recovery.cs: `x = (1 + ;`) or zero-width (EndOfFileToken) leaves.
        val ours = transformAll(recovery.root) { if (it.isToken && it.start == it.end) emptyList() else null }
        val diff = TreeDiff()
        assertEquals(emptyList<Mismatch>(), diff.diff(recovery, listOf(ours)))
        assertEquals(2, diff.missingTokens)
        assertEquals(1, diff.zeroWidthTokens)
    }

    fun testContextualKindIsNotCompared() {
        val ours = transformAll(directives.root) { if (it.contextualKind != null) listOf(it.copy(contextualKind = null)) else null }
        assertEquals(emptyList<Mismatch>(), TreeDiff().diff(directives, listOf(ours)))
    }

    fun testSkippedTokensAreRemovedFromOurTree() {
        val expected = DumpFile(
            "a.cs",
            listOf(DumpNode("CompilationUnit", 0, 5, listOf(DumpNode("EndOfFileToken", 5, 5, isToken = true)))),
            listOf(DumpTrivia("SkippedTokensTrivia", 0, 3, DumpNode("SkippedTokensTrivia", 0, 3))),
            emptyList(),
        )
        val ours = DumpNode("CompilationUnit", 0, 5, listOf(DumpNode("IdentifierToken", 0, 3, isToken = true)))
        val diff = TreeDiff()
        assertEquals(emptyList<Mismatch>(), diff.diff(expected, listOf(ours)))
        assertEquals(1, diff.skippedTokens)
    }

    fun testNodeKind() = assertOneMismatch("MultiplyExpression -> DivideExpression kind") { n ->
        if (n.kind == "MultiplyExpression") listOf(n.copy(kind = "DivideExpression")) else null
    }

    fun testTokenKind() = assertOneMismatch("IntKeyword -> LongKeyword kind") { n ->
        if (n.kind == "IntKeyword" && n.start == 69) listOf(n.copy(kind = "LongKeyword")) else null
    }

    fun testNodeEnd() = assertOneMismatch("ArrowExpressionClause range") { n ->
        if (n.kind == "ArrowExpressionClause") listOf(n.copy(end = n.end - 1)) else null
    }

    fun testNodeStart() = assertOneMismatch("DeclarationPattern range") { n ->
        if (n.kind == "DeclarationPattern") listOf(n.copy(start = n.start - 1)) else null
    }

    fun testTokenRange() = assertOneMismatch("IdentifierToken range") { n ->
        if (n.isToken && n.start == 50) listOf(n.copy(end = n.end + 1)) else null
    }

    fun testMissingToken() = assertOneMismatch("SemicolonToken missing") { n ->
        if (n.kind == "SemicolonToken" && n.start == 63) emptyList() else null
    }

    fun testMissingSubtree() = assertOneMismatch("TypeParameterConstraintClause missing") { n ->
        if (n.kind == "TypeParameterConstraintClause") emptyList() else null
    }

    fun testExtraToken() = assertOneMismatch("CommaToken extra") { n ->
        if (n.kind == "SemicolonToken" && n.start == 63) listOf(n, DumpNode("CommaToken", 64, 65, isToken = true)) else null
    }

    fun testExtraZeroWidthNodeBeforeSibling() = assertOneMismatch("OmittedArraySizeExpression extra") { n ->
        if (n.kind == "SemicolonToken" && n.start == 63) listOf(DumpNode("OmittedArraySizeExpression", 63, 63), n) else null
    }

    fun testMissingWrapper() = assertOneMismatch("EqualsValueClause missing") { n ->
        if (n.kind == "EqualsValueClause") n.children else null
    }

    fun testExtraWrapper() = assertOneMismatch("Argument extra") { n ->
        if (n.kind == "IdentifierName" && n.start == 13) listOf(DumpNode("Argument", n.start, n.end, listOf(n))) else null
    }

    fun testMismatchesAccumulateAndAllowlist() {
        val diff = TreeDiff()
        diff.diff(directives, listOf(mutate(directives.root) { n -> if (n.kind == "MultiplyExpression") listOf(n.copy(kind = "DivideExpression")) else null }))
        diff.diff(recovery, listOf(mutate(recovery.root) { n -> if (n.kind == "Block") listOf(n.copy(end = 1)) else null }))
        assertEquals(listOf("MultiplyExpression -> DivideExpression kind", "Block range"), diff.mismatches.map { it.cls })
        assertEquals("Recovery.cs:19", diff.mismatches[1].key)

        val allowlist = Allowlist.parse(listOf("# comment", "class Block range  # deliberate", "Directives.cs:58", "Other.cs:1", ""))
        val partition = allowlist.partition(diff.mismatches)
        assertEquals(0, partition.real.size)
        assertEquals(2, partition.allowed.size)
        assertEquals(listOf("Other.cs:1"), partition.stale)
    }

    private fun assertOneMismatch(cls: String, f: (DumpNode) -> List<DumpNode>?) {
        val ours = mutate(directives.root, f)
        assertNotSame("mutation did not apply", directives.root, ours)
        val mismatches = TreeDiff().diff(directives, listOf(ours))
        assertEquals(mismatches.joinToString("\n"), listOf(cls), mismatches.map { it.cls })
    }

    companion object {
        /** Replaces the first node (pre-order) for which [f] returns a list by that list. */
        fun mutate(root: DumpNode, f: (DumpNode) -> List<DumpNode>?): DumpNode {
            var done = false
            fun visit(n: DumpNode): List<DumpNode> {
                if (done) return listOf(n)
                f(n)?.let { done = true; return it }
                return listOf(n.copy(children = n.children.flatMap { visit(it) }))
            }
            val result = visit(root).single()
            return if (done) result else root
        }

        /** Replaces every node for which [f] returns a list (children of a replaced node are not visited). */
        fun transformAll(root: DumpNode, f: (DumpNode) -> List<DumpNode>?): DumpNode {
            fun visit(n: DumpNode): List<DumpNode> = f(n) ?: listOf(n.copy(children = n.children.flatMap { visit(it) }))
            return visit(root).single()
        }
    }
}
