package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode

/** The machinery of the step-6 gates that the placeholder parser cannot exercise: [ParseGuard], coverage, locality. */
class GateSupportTest : CSharpParsingTestCase("parser") {

    fun testGuardPassesTheValue() {
        val outcome = ParseGuard.run(5_000) { 42 }
        assertTrue(outcome.ok)
        assertEquals(42, outcome.value)
    }

    fun testGuardCatchesExceptionsAndStackOverflow() {
        assertEquals(ParseGuard.Kind.EXCEPTION, ParseGuard.run(5_000) { error("boom") }.kind)
        fun deep(n: Int): Int = deep(n + 1) + 1
        assertEquals(ParseGuard.Kind.STACK_OVERFLOW, ParseGuard.run(5_000) { deep(0) }.kind)
    }

    fun testGuardCancelsASlowParse() {
        val outcome = ParseGuard.run(100) {
            while (true) ProgressManager.checkCanceled()
        }
        assertEquals(ParseGuard.Kind.TIMEOUT, outcome.kind)
    }

    fun testGuardAbandonsAParseThatIgnoresCancellation() {
        val outcome = ParseGuard.run(100) {
            val until = System.nanoTime() + 600_000_000L
            while (System.nanoTime() < until) Thread.onSpinWait()
        }
        assertEquals(ParseGuard.Kind.HUNG, outcome.kind)
    }

    fun testGuardCollectsLoggedErrors() {
        val outcome = ParseGuard.interceptLoggedErrors {
            ParseGuard.run(5_000) { Logger.getInstance("csharp-psi-gate-test").error("Unbalanced tree"); 1 }
        }
        assertEquals(1, outcome.value)
        assertEquals(1, outcome.loggedErrors.size)
        assertTrue(outcome.loggedErrors.single(), "Unbalanced tree" in outcome.loggedErrors.single())
    }

    fun testCoverageOfTheParsedFile() {
        val text = "class A { int x = 1 >> 2; string s = \$\"a{x}b\"; }\n"
        assertNull(ParserGateSupport.checkTokenCoverage(createFile("A.cs", text).node, text))
    }

    fun testCoverageDetectsASplitToken() {
        val root = CompositeElement(SyntaxKind.CompilationUnit)
        root.rawAddChildren(LeafPsiElement(SyntaxKind.IdentifierToken, "cla"))
        root.rawAddChildren(LeafPsiElement(SyntaxKind.IdentifierToken, "ss"))
        val failure = ParserGateSupport.checkTokenCoverage(root, "class")
        assertNotNull(failure)
        assertTrue(failure!!, "does not align" in failure)
    }

    fun testCoverageDetectsALostToken() {
        val root = CompositeElement(SyntaxKind.CompilationUnit)
        root.rawAddChildren(LeafPsiElement(SyntaxKind.ClassKeyword, "class"))
        assertNotNull(ParserGateSupport.checkTokenCoverage(root, "class A"))
    }

    // --- locality ---------------------------------------------------------------------------------

    //                0         1         2         3         4
    //                0123456789012345678901234567890123456789012345
    private val text = "class C { void M() { a; } void N() { b; } }"

    private fun tree(mBodyEnd: Int = 25, nStart: Int = 26, extra: Boolean = false): List<DumpNode> {
        val d = nStart - 26
        val m = DumpNode(
            "MethodDeclaration", 10, mBodyEnd,
            listOf(
                DumpNode("IdentifierToken", 15, 16, isToken = true),
                DumpNode("Block", 19, mBodyEnd, listOf(DumpNode("OpenBraceToken", 19, 20, isToken = true))),
            ),
        )
        val n = DumpNode(
            "MethodDeclaration", nStart, 41 + d,
            listOf(DumpNode("IdentifierToken", 31 + d, 32 + d, isToken = true), DumpNode("Block", 35 + d, 41 + d)),
        )
        val members = if (extra) listOf(m, DumpNode("IncompleteMember", nStart - 1, nStart - 1), n) else listOf(m, n)
        return listOf(DumpNode("CompilationUnit", 0, 43 + d, listOf(DumpNode("ClassDeclaration", 0, 43 + d, members))))
    }

    fun testLocalityBodiesAndSelfContainment() {
        val bodies = FuzzLocality.bodies(tree())
        assertEquals(listOf(19, 35), bodies.map { it.block.start })
        assertEquals(2, bodies.first().depth)
        // `a` -> `aa` inside M's body: local.
        val mutated = text.substring(0, 21) + "a" + text.substring(21)
        assertSame(bodies.first(), FuzzLocality.localBody(bodies, 21, 21, mutated, 1))
        // A mutation of the body's own brace is not inside the body.
        assertNull(FuzzLocality.localBody(bodies, 19, 20, text.removeRange(19, 20), -1))
        // An unterminated block comment runs past the body: not local.
        val comment = text.substring(0, 21) + "/* " + text.substring(21)
        assertNull(FuzzLocality.localBody(bodies, 21, 21, comment, 3))
        // An extra `}` closes the body early: not local.
        val brace = text.substring(0, 21) + "} " + text.substring(21)
        assertNull(FuzzLocality.localBody(bodies, 21, 21, brace, 2))
    }

    fun testLocalityCheck() {
        val original = tree()
        val body = FuzzLocality.bodies(original).first()
        // The same tree outside M, shifted by one character after the edit.
        assertNull(FuzzLocality.check(original, tree(mBodyEnd = 26, nStart = 27), body, 22, 1))
        // N moved: the tree outside M changed.
        assertNotNull(FuzzLocality.check(original, tree(mBodyEnd = 26, nStart = 28), body, 22, 1))
        // An extra node between the members.
        assertNotNull(FuzzLocality.check(original, tree(mBodyEnd = 26, nStart = 27, extra = true), body, 22, 1))
        // M is gone.
        val withoutM = listOf(DumpNode("CompilationUnit", 0, 44, listOf(DumpNode("ClassDeclaration", 0, 44))))
        assertTrue(FuzzLocality.check(original, withoutM, body, 22, 1)!!.startsWith("no MethodDeclaration"))
    }
}
