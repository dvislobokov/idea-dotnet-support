package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFileType
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion

/**
 * Reparseable bodies in the platform (docs/csharp-psi/GRAMMAR.md, "Reparseable bodies"): text is inserted into a document at
 * `|` and committed (`BlockSupportImpl` with `DiffLog`), then
 *  - the file's tree equals a fresh parse of the new text, element types included (a body type carries its context);
 *  - for a body reparse: the innermost (or expected) body was the node the platform reparsed, and PSI outside it kept
 *    its identity;
 *  - for a fallback: no body around the edit was reparsed alone.
 */
class CSharpBodyReparseTest : BasePlatformTestCase() {

    fun testMethodBody() {
        bodyReparse("class C { void M() { int a = 1;| } int F; }", " a++;")
    }

    fun testAccessorBody() {
        bodyReparse("class C { int P { get { return 1|; } set { } } }", " + 2")
    }

    fun testConstructorWithInitializer() {
        bodyReparse("class B { B(int x) { } } class C : B { C() : base(1) { | } }", "F();")
    }

    fun testOperatorBody() {
        bodyReparse("class C { public static C operator +(C a, C b) { return |a; } }", "b ?? ")
    }

    fun testConversionOperatorAndDestructor() {
        bodyReparse("class C { public static implicit operator int(C c) { return |1; } }", "2 + ")
        bodyReparse("class C { ~C() { | } }", "Dispose();")
    }

    fun testLocalFunctionBody() {
        bodyReparse("class C { void M() { void L() { | } L(); } }", "F();", innermost = true)
    }

    fun testLambdaBody() {
        bodyReparse("class C { void M() { System.Action a = () => { | }; } }", "F();", innermost = true)
    }

    fun testAnonymousMethodBody() {
        bodyReparse("class C { void M() { System.Action a = delegate { | }; } }", "F();", innermost = true)
    }

    /** `await x;` is an await expression in an async method and a declaration of `x` with type `await` elsewhere. */
    fun testAwaitInAsyncMethod() {
        val async = bodyReparse("class C { async System.Threading.Tasks.Task M() { | } }", "await x;")
        assertTrue(async.contains("AwaitExpression"))
        val sync = bodyReparse("class C { void M() { | } }", "await x;")
        assertFalse(sync.contains("AwaitExpression"))
    }

    fun testAwaitInAsyncLambda() {
        val tree = bodyReparse("class C { void M() { F(async () => { | }); } }", "await x;", innermost = true)
        assertTrue(tree.contains("AwaitExpression"))
    }

    fun testFieldKeywordInPropertyAccessor() {
        val tree = bodyReparse("class C { int P { get { return |; } } }", "field")
        assertTrue(tree.contains("FieldExpression"))
    }

    /**
     * The file's language version reaches a body parsed alone (`BodyFileSettings`): in a C# 7.3 file (the key on its
     * `VirtualFile`) `field` in an accessor stays an identifier after the reparse, as in a full parse at 7.3; parsed
     * alone at the IDE default it would be a `FieldExpression` and the tree would differ from the fresh parse.
     */
    fun testFieldInAccessorFollowsFileLanguageVersion() {
        val old = bodyReparse("class C { int P { get { return |; } } }", "field", languageVersion = CSharpLanguageVersion.CSharp7_3)
        assertFalse(old.contains("FieldExpression"))
        assertTrue(old.contains("IdentifierName"))
        val default = bodyReparse("class C { int P { get { return |; } } }", "field", languageVersion = CSharpLanguageLevel.IDE_DEFAULT)
        assertTrue(default.contains("FieldExpression"))
    }

    fun testIteratorMethod() {
        bodyReparse("class C { System.Collections.Generic.IEnumerable<int> M() { | } }", "yield return 1;")
    }

    fun testQueryLambdaBody() {
        bodyReparse("class C { void M() { var q = from a in b select F(() => { | }); } }", "G();", innermost = true)
    }

    /** A typing session: valid → invalid → valid inside a lambda in a method; the lambda falls back to the method body when its errors change. */
    fun testTypingSequenceKeepsTreesEqual() {
        configure("class C { void M() { F(x => { | }); } void N() { } }")
        for (piece in listOf("G", ".", "H", "(", ")", ";", " var y = ", "1", ";")) typeAndCheck(piece)
    }

    fun testDirectiveInsideBodyFallsBack() {
        fallback("class C { void M() {\n#if X\n        A();\n#endif\n        |\n    } }", "B();")
    }

    fun testTypingDirectiveFallsBack() {
        fallback("class C { void M() {\n|\n} }", "#if X\n")
    }

    fun testUnbalancedOpenBraceFallsBack() {
        fallback("class C { void M() { | } void N() { } }", "{")
    }

    fun testUnbalancedCloseBraceFallsBack() {
        fallback("class C { void M() { | F(); } void N() { } }", "}")
    }

    fun testCommentSwallowingTheBraceFallsBack() {
        fallback("class C { void M() { | } void N() { } }", "/*")
    }

    /**
     * A `}` typed with a comment after it: the text still ends with `}` and the parse alone reaches EOF, but there the
     * comment swallows the old `}`; the reparse must be rejected (the real `}` does not end the text).
     */
    fun testTriviaAfterTypedBraceFallsBack() {
        fallback("class C { void M() {| } void N() { } }", "} // done")
        fallback("class C { void M() { a(); | } void N() { } }", "} /*")
        fallback("class C { void M() { F(() => {| }); } }", "} //")
        fallback("class C { void M() {| } void N() { } }", "}\n    /// <summary>")
    }

    /** `public int x;` ends a method body early in Roslyn (`IsPossibleMemberStartOrStop` of the enclosing type). */
    fun testMemberInBodyFallsBack() {
        val tree = fallback("class C { void M() { F(); | } }", "public int x;")
        assertTrue(tree.contains("FieldDeclaration"))
    }

    /** The same body text in a property accessor (`field` context) and in a method parses to different types. */
    fun testBodiesInDifferentContextsHaveDifferentTypes() {
        val file = PsiFileFactory.getInstance(project).createFileFromText("A.cs", CSharpLanguage, "class C { int P { get { return 1; } } int M() { return 1; } }")
        val bodies = bodies(file.node)
        assertEquals(2, bodies.size)
        assertNotSame(bodies[0].elementType, bodies[1].elementType)
        assertEquals("Block", bodies[0].elementType.toString())
    }

    /** An empty body with a directive gets a plain `Block`: the platform could expand a body without child markers lazily. */
    fun testEmptyBodyWithDirectiveIsPlain() {
        val file = PsiFileFactory.getInstance(project).createFileFromText("A.cs", CSharpLanguage, "class C { void M() {\n#if X\n#endif\n} void N() { } }")
        assertEquals(1, bodies(file.node).size)
    }

    // --- harness -----------------------------------------------------------------------------------

    /** Opens [textWithBar] without the bar; with [languageVersion], the file carries it (the key on its `VirtualFile`). */
    private fun configure(textWithBar: String, languageVersion: CSharpLanguageVersion? = null) {
        val offset = textWithBar.indexOf('|')
        val text = textWithBar.removeRange(offset, offset + 1)
        if (languageVersion == null) {
            myFixture.configureByText("A.cs", text)
        } else {
            val virtualFile = LightVirtualFile("A.cs", CSharpFileType, text)
            virtualFile.putUserData(CSharpLanguageLevel.KEY, languageVersion)
            myFixture.configureFromExistingVirtualFile(virtualFile)
        }
        myFixture.editor.caretModel.moveToOffset(offset)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        structure()
    }

    private fun structure() = BodyReparseSupport.structure(myFixture.file.node)

    /** Inserts [text] at the caret, commits, checks the tree against a fresh parse; returns the reparsed body or null. */
    private fun typeAndCheck(text: String): ASTNode? {
        val document = myFixture.editor.document
        val offset = myFixture.editor.caretModel.offset
        val accepted = CSharpBodyBlockType.acceptedCount()
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(offset, text)
            myFixture.editor.caretModel.moveToOffset(offset + text.length)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val fresh = freshParse(document.text)
        val expected = BodyReparseSupport.structure(fresh.node)
        val actual = structure()
        BodyReparseSupport.firstDifference(expected, actual)?.let { fail("after typing `$text`: incremental tree differs from a fresh parse $it\n${document.text}") }
        return if (CSharpBodyBlockType.acceptedCount() > accepted) CSharpBodyBlockType.lastAcceptedReparse() else null
    }

    /** A full parse of [text] at the language version of the file under test. */
    private fun freshParse(text: String): com.intellij.psi.PsiFile {
        val version = myFixture.file.virtualFile?.getUserData(CSharpLanguageLevel.KEY)
            ?: return PsiFileFactory.getInstance(project).createFileFromText("B.cs", CSharpLanguage, text)
        val virtualFile = LightVirtualFile("B.cs", CSharpFileType, text)
        virtualFile.putUserData(CSharpLanguageLevel.KEY, version)
        return PsiManager.getInstance(project).findFile(virtualFile)!!
    }

    private fun bodies(root: ASTNode): List<ASTNode> {
        val out = ArrayList<ASTNode>()
        fun walk(n: ASTNode) {
            if (n.elementType is CSharpBodyBlockType) out += n
            var c = n.firstChildNode
            while (c != null) {
                walk(c)
                c = c.treeNext
            }
        }
        walk(root)
        return out
    }

    /** The bodies containing [offset], innermost first. */
    private fun bodiesAround(offset: Int): List<ASTNode> =
        bodies(myFixture.file.node).filter { it.startOffset < offset && offset < it.startOffset + it.textLength }.sortedBy { it.textLength }

    /**
     * Types [text] at `|` of [textWithBar]; the reparsed node must be the innermost body around the edit when
     * [innermost], else the outermost one (the member body). Returns the structure of the new tree.
     */
    private fun bodyReparse(
        textWithBar: String, text: String, innermost: Boolean = false, languageVersion: CSharpLanguageVersion? = null,
    ): String {
        configure(textWithBar, languageVersion)
        val offset = myFixture.editor.caretModel.offset
        val around = bodiesAround(offset)
        assertFalse("no reparseable body around the edit", around.isEmpty())
        val expected = if (innermost) around.first() else around.last()
        val outside = outsideLeaves(around.last())
        val reparsed = typeAndCheck(text)
        assertNotNull("no body was reparsed alone", reparsed)
        assertSame("reparsed node", expected, reparsed)
        assertTrue(expected.psi.isValid)
        for (leaf in outside) assertTrue("PSI outside the body lost its identity: $leaf", leaf.isValid && leaf.containingFile == myFixture.file)
        return structure().joinToString("\n")
    }

    private fun fallback(textWithBar: String, text: String): String {
        configure(textWithBar)
        val reparsed = typeAndCheck(text)
        assertNull("a body was reparsed alone: ${reparsed?.text}", reparsed)
        return structure().joinToString("\n")
    }

    /** Leaves before and after [body] (the first and last of the file and those next to it). */
    private fun outsideLeaves(body: ASTNode): List<PsiElement> = listOfNotNull(
        TreeUtil.findFirstLeaf(myFixture.file.node), TreeUtil.prevLeaf(body), TreeUtil.nextLeaf(body), TreeUtil.findLastLeaf(myFixture.file.node),
    ).filter { it.textLength > 0 }.map { it.psi }
}
