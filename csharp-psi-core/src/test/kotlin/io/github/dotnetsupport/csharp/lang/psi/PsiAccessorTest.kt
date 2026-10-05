package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpElementType
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType
import io.github.dotnetsupport.csharp.lang.parser.SliceParseHarness
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpBlockImpl
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpElementImpl
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpStubElementImpl
import java.nio.file.Paths

/**
 * Fast counterpart of the PSI accessor gate ([PsiAccessorCheck], `*PsiAccessorCorpusTest`): the snippets of
 * `testData/parser/slice` (`expr`, `stmt`) and `testData/parser/decl` (`member`, `cs`), valid and invalid, against
 * `roslyndump --fields`, and the doc comments of `testData/parser/doc`; plus the factory, the visitor, the file's root
 * and the doc comment element.
 */
class PsiAccessorTest : CSharpParsingTestCase("parser") {

    fun testAccessorsOnParserSnippets() {
        val check = PsiAccessorCheck()
        val slice = Paths.get(CSharpTestUtil.testDataPath("parser/slice"))
        val decl = Paths.get(CSharpTestUtil.testDataPath("parser/decl"))
        val doc = Paths.get(CSharpTestUtil.testDataPath("parser/doc"))
        val runs = listOf(
            slice to SliceParseHarness.Mode.Expr,
            slice to SliceParseHarness.Mode.Stmt,
            decl to SliceParseHarness.Mode.Member,
            decl to SliceParseHarness.Mode.File,
            doc to SliceParseHarness.Mode.File,
        )
        for ((root, mode) in runs) {
            val run = RoslynDump.run(mode.dumpMode, root, fields = true)
            for (dump in run.files) {
                val text = CSharpLexerDiffCorpusTest.readSource(root.resolve(dump.path))
                val result = SliceParseHarness.parse(text, mode)
                check.check(dump.path, dump.roots, result.root, dump.trivia)
            }
        }
        val report = check.mismatches.take(40).joinToString("\n") { "  $it" }
        println(
            "PsiAccessorTest: files=${check.files} nodes=${check.nodes} accessorChecks=${check.accessorChecks} " +
                "accessorMismatches=${check.accessorMismatches} nodesWithoutPsiClass=${check.nodesWithoutPsiClass} " +
                "elementsWithWrongClass=${check.elementsWithWrongClass} alignmentMismatches=${check.alignmentMismatches} " +
                "docComments=${check.docComments}",
        )
        if (report.isNotEmpty()) println(report)
        assertTrue("too few checks: ${check.accessorChecks}", check.accessorChecks > 10_000)
        assertTrue("too few doc comments: ${check.docComments}", check.docComments >= 30)
        assertEquals("mismatches:\n$report", 0, check.mismatches.size)
    }

    /** Every node kind of `SyntaxKind` (element types, not tokens or trivia) that Syntax.xml gives a class has one. */
    fun testEveryElementTypeKindHasAClass() {
        val kinds = SyntaxKind.byName.filterValues { it is CSharpElementType }.keys
        val without = kinds.filter { it !in CSharpPsiFieldTable.classByKind }.sorted()
        assertEquals("node kinds without a generated class", emptyList<String>(), without)
    }

    fun testFileRootBodyBlockAndVisitor() {
        val file = createFile("a.cs", "class C { void M() { a = b + c; } }") as CSharpFile
        val unit = file.compilationUnit!!
        val type = unit.members.single() as CSharpClassDeclaration
        assertEquals("C", type.identifier!!.text)
        val method = type.members.single() as CSharpMethodDeclaration
        val body = method.body!!
        assertTrue(CSharpBodyBlockType.isBlock(body.node.elementType))
        assertTrue(body.node.elementType is CSharpBodyBlockType)
        assertInstanceOf(body, CSharpBlockImpl::class.java)
        val assignment = (body.statements.single() as CSharpExpressionStatement).expression as CSharpAssignmentExpression
        val sum = assignment.right as CSharpBinaryExpression
        assertEquals("+", sum.operatorToken!!.text)
        assertEquals("b", (sum.left as CSharpIdentifierName).identifier!!.text)

        // Every composite reaches visitCSharpElement through its chain; visitBinaryExpression sees the sum.
        val visited = ArrayList<PsiElement>()
        val binaries = ArrayList<CSharpBinaryExpression>()
        val expressions = ArrayList<CSharpExpression>()
        file.accept(object : CSharpVisitor() {
            override fun visitElement(element: PsiElement) {
                element.acceptChildren(this)
            }

            override fun visitCSharpElement(o: CSharpElement) {
                visited += o
                super.visitCSharpElement(o)
            }

            override fun visitBinaryExpression(o: CSharpBinaryExpression) {
                binaries += o
                super.visitBinaryExpression(o)
            }

            override fun visitExpression(o: CSharpExpression) {
                expressions += o
                super.visitExpression(o)
            }
        })
        val composites = PsiTreeUtil.collectElements(file) { it.node is CompositeElement && it.node.elementType != TokenType.ERROR_ELEMENT && it !== file }
        assertEquals(composites.toList(), visited)
        assertTrue(composites.all { it is CSharpElementImpl || it is CSharpStubElementImpl })
        assertEquals(listOf(sum), binaries)
        assertEquals(listOf("void", "a = b + c", "a", "b + c", "b", "c"), expressions.map { it.text })
    }

    /** Separated lists: elements and separators; a missing token is null, missing separators are left out. */
    fun testSeparatedListAndMissingTokens() {
        val file = createFile("a.cs", "class C { void M() { F(a, b c); } }") as CSharpFile
        val call = PsiTreeUtil.findChildOfType(file, CSharpInvocationExpression::class.java)!!
        val args = call.argumentList!!
        assertEquals(listOf("a", "b", "c"), args.arguments.map { it.text })
        assertEquals(listOf(","), args.argumentsSeparators.map { it.text })
        val method = PsiTreeUtil.findChildOfType(createFile("b.cs", "class C { void M( }"), CSharpMethodDeclaration::class.java)!!
        assertNull(method.parameterList!!.closeParenToken)
        assertNotNull(method.parameterList!!.openParenToken)
    }

    /** A doc comment: a `PsiComment` that is a [CSharpDocumentationCommentTrivia]; XML and cref nodes typed; missing tokens null. */
    fun testDocCommentPsi() {
        val file = createFile("a.cs", "/// <summary>Hi <see cref=\"A.B(int)\"/></summary>\n/// <param name=\"x\n class C { }")
        val comment = PsiTreeUtil.findChildOfType(file, PsiComment::class.java)!!
        assertInstanceOf(comment, CSharpDocumentationCommentTrivia::class.java)
        val doc = comment as CSharpDocumentationCommentTrivia
        assertEquals("", doc.endOfComment!!.text)
        val summary = doc.content.filterIsInstance<CSharpXmlElement>().first()
        assertEquals("summary", summary.startTag!!.nameElement!!.localName!!.text)
        assertEquals("summary", summary.endTag!!.nameElement!!.localName!!.text)
        val see = summary.content.filterIsInstance<CSharpXmlEmptyElement>().single()
        val cref = see.attributes.single() as CSharpXmlCrefAttribute
        val member = cref.cref as CSharpQualifiedCref
        assertEquals("A", member.container!!.text)
        val method = member.member as CSharpNameMemberCref
        assertEquals("int", method.parameters!!.parameters.single().type!!.text)
        assertEquals("/>", see.slashGreaterThanToken!!.text)
        // `<param name="x` + end of comment: the end quote and `/>` or `>` are missing.
        val param = doc.content.filterIsInstance<CSharpXmlElement>().singleOrNull { it.startTag!!.nameElement!!.localName!!.text == "param" }
            ?: doc.content.filterIsInstance<CSharpXmlEmptyElement>().single { it.nameElement!!.localName!!.text == "param" }
        val nameAttribute = PsiTreeUtil.findChildOfType(param, CSharpXmlNameAttribute::class.java)!!
        assertEquals("\"", nameAttribute.startQuoteToken!!.text)
        assertNull(nameAttribute.endQuoteToken)

        // A CSharpVisitor sees the doc comment and its nodes; another visitor sees a comment.
        val kinds = ArrayList<String>()
        comment.accept(object : CSharpVisitor() {
            override fun visitDocumentationCommentTrivia(o: CSharpDocumentationCommentTrivia) {
                kinds += "doc"
                o.content.forEach { it.accept(this) }
            }

            override fun visitXmlElement(o: CSharpXmlElement) {
                kinds += "element"
            }
        })
        assertEquals("doc", kinds.first())
        assertTrue(kinds.toString(), "element" in kinds)
        var asComment = false
        comment.accept(object : PsiElementVisitor() {
            override fun visitComment(c: PsiComment) {
                asComment = true
            }
        })
        assertTrue(asComment)
    }
}
