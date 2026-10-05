package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentElementType
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.doc.DocCommentTreeBuilder

/**
 * Doc comments (step 5): goldens of the PSI of `testData/parser/doc/<Name>.cs` (the same files [DocCommentDiffTest]
 * checks against Roslyn), and the element's contract: a lazily parsed comment that reparses on its own.
 */
class DocCommentParsingTest : CSharpParsingTestCase("parser/doc") {

    fun testXmlElements() = doTest(false)
    fun testCrefForms() = doTest(false)
    fun testNameAttributes() = doTest(false)
    fun testMultiLine() = doTest(false)
    fun testMalformedXml() = doTest(false)
    fun testUnterminated() = doTest(false)

    /** Regression: missing end tags before an empty last `///` line go after its `///`, as Roslyn's (review 2). */
    fun testEmptyLastLine() = doTest(false)

    /** Builds [text] directly (no fallback), checks the leaves spell it and returns the top-level children. */
    private fun build(text: String, delimited: Boolean): List<ASTNode> {
        val first = DocCommentTreeBuilder.build(text, delimited, null)
        val top = generateSequence(first as ASTNode?) { it.treeNext }.toList()
        val sb = StringBuilder()
        // Iterative walk: the trees of these inputs are thousands of levels deep.
        val work = ArrayList<ASTNode>(top.reversed())
        while (work.isNotEmpty()) {
            val n = work.removeAt(work.size - 1)
            if (n is LeafElement) sb.append(n.chars)
            else generateSequence(n.firstChildNode) { it.treeNext }.toList().asReversed().forEach { work += it }
        }
        assertEquals(text, sb.toString())
        return top
    }

    private fun timed(name: String, body: () -> Unit) {
        val started = System.nanoTime()
        body()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("$name took $millis ms", millis < 5_000)
    }

    /**
     * Regression (review 1): deep nesting overflowed the stack (parser recursion, recursive tree building). XML
     * nesting past the depth limit leaves the rest as text; deep crefs keep their tree.
     */
    fun testDeepNesting() {
        val n = 10_000
        timed("type arguments") { build("/// <see cref=\"M(" + "A{".repeat(n) + "\"/>", false) }
        timed("elements") {
            val top = build("/// " + "<a>".repeat(n), false)
            assertTrue(top.any { it.elementType == SyntaxKind.XmlElement })
            assertEquals(SyntaxKind.XmlText, top[top.size - 2].elementType)
        }
        timed("delimited elements") { build("/** " + "<a>".repeat(2 * n) + " */", true) }
        timed("qualified cref") { build("/// <see cref=\"" + "A.".repeat(n) + "\"/>", false) }
        timed("pointer cref") { build("/// <see cref=\"M(int" + "*".repeat(n) + ")\"/>", false) }
        timed("extension crefs") { build("/// <see cref=\"" + "extension(int).".repeat(n) + "M\"/>", false) }
        timed("lazy element") {
            val doc = docComments("/// " + "<a>".repeat(n) + "\nclass C { }\n").single()
            assertEquals(doc.text, doc.getChildren(null).joinToString("") { it.text })
        }
    }

    /** Regression (review 3): a text that is not a doc comment of its kind made the lexer stop moving. */
    fun testNoProgressInputs() {
        for (text in listOf("/***/", "/**/", "/** */", "/**", "///", "")) build(text, true)
        // Not reachable through the lexer (a `////` line is no doc comment): ends quickly, with the fallback of parseContents.
        val failure = runCatching { DocCommentTreeBuilder.build("//// x", false, null) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException)
        for (text in listOf("///", "/// x", "")) build(text, false)
    }

    /** Regression (review 4): Roslyn hangs on this input (docs/csharp-psi/GRAMMAR.md, "Doc comments"); ours must not. */
    fun testRoslynHangInput() {
        timed("U+FFFF in cref") {
            build("/// <see cref=\"A￿B\"/>", false)
            build("/** <see cref=\"A￿B\"/> */", true)
            build("/// <param name=\"A￿B\"/> <a b=\"￿\">￿</a>", false)
            val doc = docComments("/// <see cref=\"A￿B\"/>\nclass C { }\n").single()
            assertEquals(doc.text, doc.getChildren(null).joinToString("") { it.text })
        }
    }

    private fun docComments(text: String): List<ASTNode> {
        val file = createFile("a.cs", text)
        val out = ArrayList<ASTNode>()
        fun walk(n: ASTNode) {
            if (n.elementType is CSharpDocCommentElementType) {
                out += n
                return
            }
            var c = n.firstChildNode
            while (c != null) {
                walk(c)
                c = c.treeNext
            }
        }
        walk(file.node)
        return out
    }

    fun testLazyAndComment() {
        val docs = docComments("/// <summary>S</summary>\nclass C { /** <see cref=\"C\"/> */ void M() { } }\n")
        assertEquals(2, docs.size)
        for (d in docs) {
            assertTrue(d is LazyParseableElement)
            assertFalse("parsed before access: $d", (d as LazyParseableElement).isParsed)
            assertTrue(d.psi is PsiComment)
            assertEquals(d.elementType, (d.psi as PsiComment).tokenType)
        }
        assertEquals(SyntaxKind.SingleLineDocumentationCommentTrivia, docs[0].elementType)
        assertEquals(SyntaxKind.MultiLineDocumentationCommentTrivia, docs[1].elementType)
        // Expanding: the children spell the text, the first one is the exterior.
        for (d in docs) {
            assertEquals(SyntaxKind.DocumentationCommentExteriorTrivia, d.firstChildNode.elementType)
            assertEquals(d.text, d.getChildren(null).joinToString("") { it.text })
        }
        assertTrue(PsiTreeUtil.collectElementsOfType(docs[0].psi.containingFile, PsiComment::class.java).size >= 2)
    }

    fun testReparseable() {
        val single = docComments("/// <a/>\nclass C { }\n").single()
        val multi = docComments("/** <a/> */\nclass C { }\n").single()
        val singleType = SyntaxKind.SingleLineDocumentationCommentTrivia as CSharpDocCommentElementType
        val multiType = SyntaxKind.MultiLineDocumentationCommentTrivia as CSharpDocCommentElementType
        assertTrue(singleType.isReparseable(single, "/// <b>x</b>\n/// y", CSharpLanguage, project))
        assertFalse("a new line at the end ends the comment", singleType.isReparseable(single, "/// <b/>\n", CSharpLanguage, project))
        assertFalse("not a doc comment", singleType.isReparseable(single, "//// x", CSharpLanguage, project))
        assertFalse("code after the comment", singleType.isReparseable(single, "/// x\nclass", CSharpLanguage, project))
        assertTrue(multiType.isReparseable(multi, "/** <b/>\n * x */", CSharpLanguage, project))
        assertFalse("unterminated", multiType.isReparseable(multi, "/** <b/>", CSharpLanguage, project))
        assertFalse("code after the comment", multiType.isReparseable(multi, "/** a */ class", CSharpLanguage, project))
        assertFalse("another kind", multiType.isReparseable(multi, "/// a */", CSharpLanguage, project))
    }
}
