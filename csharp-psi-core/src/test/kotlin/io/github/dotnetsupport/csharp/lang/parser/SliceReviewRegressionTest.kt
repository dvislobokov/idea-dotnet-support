package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.PsiBuilderFactory
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode

/** Regression tests for the review findings of the step-0 slice that the oracle diff cannot express. */
class SliceReviewRegressionTest : CSharpParsingTestCase("parser/slice") {

    /** `containsTernaryCollectionToReinterpret` counts only conditionals nested in the when-true part (LP 11769). */
    fun testConditionalReparsesOnlyForNestedTernaryCollections() {
        assertEquals(0, SliceParseHarness.parse("a ? [1]", false).conditionalReparses)
        assertEquals(0, SliceParseHarness.parse("a ? [1] : b", false).conditionalReparses)
        assertEquals(0, SliceParseHarness.parse("a ? b : c", false).conditionalReparses)
        // `a ? b?[c] : d`: the first parse reads `b ? [c] : d` as a nested ternary with a collection and misses the `:`.
        assertEquals(1, SliceParseHarness.parse("a ? b?[c] : d", false).conditionalReparses)
        // `a ? b ? [c] : d : e`: the nested ternary with a collection has its colon, nothing to retry.
        assertEquals(0, SliceParseHarness.parse("a ? b ? [c] : d : e", false).conditionalReparses)
    }

    /** `SkipBadTokensWithExpectedKind` (LP 4621): one error element and one error for a run of skipped tokens. */
    fun testSkippedListTokensProduceOneError() {
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition(), CSharpLexer(), "] ] , b")
        val root = builder.mark()
        val parser = LanguageParser(builder)
        val action = parser.skipBadSeparatedListTokensWithExpectedKind(
            isNotExpected = { parser.currentKind !== SyntaxKind.CommaToken },
            abort = { false },
            SyntaxKind.CommaToken, SyntaxKind.CloseParenToken,
        )
        assertEquals(PostSkipAction.Continue, action)
        assertSame(SyntaxKind.CommaToken, parser.currentKind)
        assertEquals(1, parser.errorCount)
        parser.consumeUnexpectedTokens()
        root.done(CSharpParserDefinition.FILE)
        val ast = builder.treeBuilt
        var errors = 0
        var c = ast.firstChildNode
        while (c != null) {
            if (c.elementType == com.intellij.psi.TokenType.ERROR_ELEMENT) errors++
            c = c.treeNext
        }
        assertEquals("error elements: one for `] ]`, one for the unexpected tail", 2, errors)
    }

    /** A reset point restores `scanTypeLastTokenKind`. */
    fun testResetPointRestoresScanState() {
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition(), CSharpLexer(), "int F() => 1; c;")
        val root = builder.mark()
        val parser = LanguageParser(builder)
        parser.scanTypeLastTokenKind = SyntaxKind.IdentifierToken
        val rp = parser.getResetPoint()
        parser.scanTypeLastTokenKind = null
        parser.parseStatement()
        rp.reset()
        assertSame(SyntaxKind.IdentifierToken, parser.scanTypeLastTokenKind)
        rp.release()
        root.drop()
    }

    /**
     * A local function starting with `unsafe (` (once consumed into an error element as out of the slice) is a
     * `LocalFunctionStatement` since step 5, and the statements after it are parsed.
     */
    fun testUnsafeLocalFunctionStopsAfterItsBody() {
        for ((text, statement) in listOf(
            "{ unsafe (int, int) F() { b(); } c(); }" to true,
            "() => { unsafe (int, int) F() { b(); } c(); }" to false,
        )) {
            val r = SliceParseHarness.parse(text, statement)
            val localFunctions = ArrayList<DumpNode>()
            fun walkLocal(n: DumpNode) {
                if (n.kind == "LocalFunctionStatement" && n.start == text.indexOf("unsafe")) localFunctions += n
                n.children.forEach { walkLocal(it) }
            }
            r.roots.forEach { walkLocal(it) }
            assertEquals("LocalFunctionStatement for `unsafe (int, int) F()` in: $text", 1, localFunctions.size)
            val c = text.indexOf("c();")
            val found = ArrayList<DumpNode>()
            fun walk(n: DumpNode) {
                if (n.kind == "ExpressionStatement" && n.start == c) found += n
                n.children.forEach { walk(it) }
            }
            r.roots.forEach { walk(it) }
            assertEquals("ExpressionStatement for `c();` in: $text", 1, found.size)
        }
    }
}
