package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.CSharpTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.parser.LanguageParser
import io.github.dotnetsupport.csharp.lang.parser.SyntaxParser
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpForStatementImpl
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpSyntaxShape

/**
 * The slot cache of `CSharpElementImpl` (accessors stay O(1) after the first and follow tree changes) and the missing
 * tokens' gaps (`CSharpMissingTokenType`: the kind is structural, not read from a message).
 */
class PsiSlotsTest : CSharpParsingTestCase("parser") {

    /** 5000 members each asking their class for its name, 500 member lookups: linear, not quadratic (was 538 ms / 84 ms). */
    fun testAccessorsOfALargeClassAreNotQuadratic() {
        val n = 5000
        val text = buildString {
            append("class Big\n{\n")
            for (i in 0 until n) append("    void M").append(i).append("() { }\n")
            append("}\n")
        }
        val file = createFile("big.cs", text)
        val cls = PsiTreeUtil.findChildOfType(file, CSharpClassDeclaration::class.java)!!
        val members = PsiTreeUtil.getChildrenOfTypeAsList(cls, CSharpMethodDeclaration::class.java)
        assertEquals(n, members.size)
        val t0 = System.nanoTime()
        for (m in members) assertEquals("Big", (m.parent as CSharpClassDeclaration).identifier!!.text)
        for (i in 0 until 500) assertSame(members[i * 10], cls.members[i * 10])
        val millis = (System.nanoTime() - t0) / 1_000_000
        assertTrue("5000 parent identifiers + 500 member lookups took $millis ms", millis < 100)
    }

    /** A change in the node's children (here: its identifier removed) is seen by the next accessor call. */
    fun testSlotCacheFollowsTreeChanges() {
        val file = createFile("a.cs", "class A { }")
        val cls = PsiTreeUtil.findChildOfType(file, CSharpClassDeclaration::class.java)!!
        val identifier = cls.identifier!!
        assertEquals("A", identifier.text)
        DebugUtil.performPsiModification<RuntimeException>("PsiSlotsTest") { (identifier.node as TreeElement).rawRemove() }
        assertNull(cls.identifier)
        assertEquals("{", cls.openBraceToken!!.text)
    }

    /**
     * Every token kind round-trips through a gap: `createMissingToken(kind)` makes a [CSharpMissingTokenType] of that
     * kind (one type per kind, so kinds whose messages coincide, `'"' expected` for `DoubleQuoteToken` and
     * `InterpolatedStringEndToken`, stay apart), with an error element when reported and none otherwise.
     */
    fun testMissingTokenGapsRoundTripEveryKind() {
        val kinds = SyntaxKind.byName.values.filterIsInstance<CSharpTokenType>().distinct()
        assertTrue(kinds.size > 100)
        assertEquals(kinds.size, kinds.map { CSharpMissingTokenType.of(it) }.toSet().size)
        assertEquals(SyntaxParser.expectedMessage(SyntaxKind.DoubleQuoteToken), SyntaxParser.expectedMessage(SyntaxKind.InterpolatedStringEndToken))
        assertNotSame(CSharpMissingTokenType.of(SyntaxKind.DoubleQuoteToken), CSharpMissingTokenType.of(SyntaxKind.InterpolatedStringEndToken))
        for (kind in kinds) {
            for (report in listOf(true, false)) {
                val gap = gapOf(kind, report)
                assertSame(kind, CSharpSyntaxShape.gapKind(gap))
                assertTrue(CSharpSyntaxShape.isGap(gap))
                val error = gap.firstChildNode?.psi as? PsiErrorElement
                if (report) assertEquals(SyntaxParser.expectedMessage(kind), error?.errorDescription) else assertNull(gap.firstChildNode)
            }
        }
    }

    /** The gap built by [SyntaxParser.createMissingToken] over an empty text. */
    private fun gapOf(kind: com.intellij.psi.tree.IElementType, report: Boolean): ASTNode {
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition(), CSharpLexer(), "")
        val root = builder.mark()
        LanguageParser(builder).createMissingToken(kind, report)
        root.done(CSharpParserDefinition.FILE)
        val gap = builder.treeBuilt.firstChildNode
        assertTrue("$kind: ${gap?.elementType}", gap?.elementType is CSharpMissingTokenType)
        return gap
    }

    /**
     * An unreported missing token is a gap too, so a later token of its kind is not taken by the earlier field: a
     * `for` header whose first `;` is missing without a diagnostic and whose second is real. The first field is null,
     * the second gets the `;` (without the gap the first field would take it).
     */
    fun testUnreportedMissingTokenKeepsItsField() {
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition(), CSharpLexer(), "for (; ) ;")
        val root = builder.mark()
        val parser = LanguageParser(builder)
        val m = builder.mark()
        parser.eatToken(SyntaxKind.ForKeyword)
        parser.eatToken(SyntaxKind.OpenParenToken)
        parser.createMissingToken(SyntaxKind.SemicolonToken, report = false)
        parser.eatToken(SyntaxKind.SemicolonToken)
        parser.eatToken(SyntaxKind.CloseParenToken)
        val body = builder.mark()
        parser.eatToken(SyntaxKind.SemicolonToken)
        body.done(SyntaxKind.EmptyStatement)
        m.done(SyntaxKind.ForStatement)
        root.done(CSharpParserDefinition.FILE)
        val node = builder.treeBuilt.firstChildNode
        assertSame(SyntaxKind.ForStatement, node.elementType)
        val forStatement = CSharpForStatementImpl(node)
        assertNull(forStatement.firstSemicolonToken)
        assertEquals(5, forStatement.secondSemicolonToken!!.textOffset)
        assertNull(PsiTreeUtil.findChildOfType(forStatement, PsiErrorElement::class.java))
    }

    /** `using unsafe static N;`: the unreported missing `static` keeps Roslyn's slot order (before `unsafe`), the name is found. */
    fun testMissingStaticOfUsingUnsafeStatic() {
        val file = createFile("a.cs", "using unsafe static N;")
        val using = PsiTreeUtil.findChildOfType(file, CSharpUsingDirective::class.java)!!
        assertNull(using.staticKeyword)
        assertEquals("unsafe", using.unsafeKeyword!!.text)
        assertEquals("N", using.namespaceOrType!!.text)
        assertEquals(";", using.semicolonToken!!.text)
    }
}
