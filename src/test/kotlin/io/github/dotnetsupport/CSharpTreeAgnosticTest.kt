package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpClassDeclaration
import io.github.dotnetsupport.ef.EfLineMarkerProvider
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpHighlightErrorFilter
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.CSharpRenameVeto
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.HeuristicCSharpFile
import io.github.dotnetsupport.lang.HeuristicCSharpSyntaxModel
import io.github.dotnetsupport.run.MainMethodRunLineMarkerContributor
import io.github.dotnetsupport.testing.DotNetTestRunLineMarkerContributor
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes as NativeTokenTypes

/** The host code that walks the PSI of a C# file works on the heuristic tree and on the native one alike (CSHARP_PSI_MIGRATION.md, step 7). */
class CSharpTreeAgnosticTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    /** [check] once per tree, on a file of its own. */
    private fun onBothTrees(name: String, text: String, check: (PsiFile, Boolean) -> Unit) {
        for (native in listOf(false, true)) {
            CSharpSyntaxTrees.forceNativeTreeForTests(native)
            val file = myFixture.configureByText("$name${if (native) "Native" else "Heuristic"}.cs", text)
            assertEquals(native, file !is HeuristicCSharpFile)
            check(file, native)
        }
    }

    private fun leafAt(file: PsiFile, text: String, occurrence: String): PsiElement = file.findElementAt(text.indexOf(occurrence))!!

    private fun leaves(file: PsiFile): List<PsiElement> = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toList()

    fun testMainIsMarkedOnBothTrees() {
        val text = """
            namespace App
            {
                /// <summary>The entry point.</summary>
                public static class Program
                {
                    [System.STAThread] // a comment between
                    public static async Task<int> Main(string[] args) { return 0; }
                    public void Main(int x) { }
                    int Other() => 1;
                }
            }
        """.trimIndent()
        onBothTrees("MainMarker", text) { file, native ->
            val marked = leaves(file).filter { MainMethodRunLineMarkerContributor().getInfo(it) != null }
            assertEquals("native=$native", listOf(text.indexOf("Main(string")), marked.map { it.textRange.startOffset })
        }
    }

    fun testTestMethodsAreMarkedOnBothTrees() {
        val text = "using Xunit;\nnamespace Shop.Tests\n{\n    public class CalcTests\n    {\n        [Fact]\n        public void Adds() { }\n\n        public void Helper() { }\n    }\n}\n"
        onBothTrees("TestMarkers", text) { file, native ->
            val contributor = DotNetTestRunLineMarkerContributor()
            assertNotNull("native=$native", contributor.getInfo(leafAt(file, text, "Adds")))
            assertNotNull("native=$native", contributor.getInfo(leafAt(file, text, "CalcTests")))
            assertNull("native=$native", contributor.getInfo(leafAt(file, text, "Helper")))
            assertNull("native=$native", contributor.getInfo(leafAt(file, text, "void Adds")))
        }
    }

    /** The provider itself answers only while a solution is loaded in the server, which a test has not; its filter of leaves is this. */
    fun testIdentifiersOfBothTrees() {
        val text = "class Person { string Name => \"Name\"; }"
        onBothTrees("Identifiers", text) { file, native ->
            assertTrue("native=$native", CSharpLeaves.isIdentifier(leafAt(file, text, "Person")))
            assertFalse("native=$native", CSharpLeaves.isIdentifier(leafAt(file, text, "class")))
            assertFalse("native=$native", CSharpLeaves.isIdentifier(leafAt(file, text, "\"Name\"")))
        }
    }

    /** Import completion is not offered in a string or a comment ([CSharpLeaves.isInStringOrComment] is its check). */
    fun testStringsAndCommentsOfBothTrees() {
        val text = "/// <summary>Doc WriteLi</summary>\nclass A\n{\n    // WriteLi\n    /* WriteLi */\n    string s = \"WriteLi\";\n    string r = \$\"x {WriteLi} WriteLi\";\n    void M() { WriteLi(); }\n}\n"
        onBothTrees("StringsAndComments", text) { file, native ->
            fun inside(offset: Int) = CSharpLeaves.isInStringOrComment(file.findElementAt(offset)!!)
            assertTrue("doc, native=$native", inside(text.indexOf("Doc")))
            assertTrue("line, native=$native", inside(text.indexOf("// WriteLi") + 3))
            assertTrue("block, native=$native", inside(text.indexOf("/* WriteLi") + 3))
            assertTrue("string, native=$native", inside(text.indexOf("\"WriteLi\"") + 1))
            assertFalse("code, native=$native", inside(text.indexOf("WriteLi();")))
            // of an interpolated string the heuristic lexer has one token, the native one the text apart from the holes
            if (native) assertFalse("hole", inside(text.indexOf("{WriteLi}") + 1))
        }
    }

    fun testRenameIsVetoedForDeclarationsOfBothTrees() {
        val text = "namespace N { class Order { void Pay() { } } }"
        onBothTrees("RenameVeto", text) { file, native ->
            val veto = CSharpRenameVeto()
            assertFalse(veto.value(leafAt(file, text, "Pay")))
            if (!native) {
                assertTrue(veto.value(PsiTreeUtil.findChildrenOfType(file, CSharpDeclaration::class.java).first { it.name == "Order" }))
                return@onBothTrees
            }
            val type = PsiTreeUtil.findChildOfType(file, CSharpClassDeclaration::class.java)!!
            // until the native CSharpSyntaxModel is in charge the heuristic one knows no native element: the veto is whatever the model says
            val model = CSharpSyntaxModel.current
            assertEquals(model.declarationOf(type) != null, veto.value(type))
            if (model !== HeuristicCSharpSyntaxModel) assertTrue(veto.value(type))
        }
    }

    /** The platform does not paint the error elements of the tree: the errors are Roslyn's, with «Errors and warnings» = Built-in. */
    fun testSyntaxErrorsOfTheNativeTreeAreNotHighlighted() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val settings = io.github.dotnetsupport.lsp.RoslynLanguageServerSettings.getInstance()
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        settings.setSource(io.github.dotnetsupport.lang.CSharpFeature.DIAGNOSTICS, io.github.dotnetsupport.lang.CSharpFeatureSource.ROSLYN)
        try {
            val file = myFixture.configureByText("BrokenNative.cs", "class A\n{\n    void M( { int x = ; }\n    int\n}\n")
            assertFalse(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty())
            assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
        } finally {
            settings.state.features = mutableMapOf()
            settings.state.enabled = io.github.dotnetsupport.lsp.RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
        }
    }

    /** The editors of the debugger parse an expression as a whole file: on the native tree that is a file with errors. */
    fun testErrorsOfADebuggerExpressionAreNotHighlighted() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        // as DotNetEditorsProvider.createExpressionCodeFragment makes it
        val fragment = PsiFileFactory.getInstance(project).createFileFromText("expression.cs", CSharpFileType, "order.Total + ", 0, false)
        val errors = PsiTreeUtil.findChildrenOfType(fragment, PsiErrorElement::class.java)
        assertFalse(errors.isEmpty())
        assertTrue(errors.none { CSharpHighlightErrorFilter().shouldHighlightErrorElement(it) })
    }

    /** The commenter, the spellchecker and `PsiTreeUtil.skipWhitespacesAndComments` go by the PSI classes of the leaves. */
    fun testTriviaOfTheNativeTreeAreCommentsAndWhitespace() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val text = "/// <summary>Doc</summary>\nclass A\n{\n    // line\n    /* block */\n#if DEBUG\n    int x;\n#endif\n    int y;\n}\n"
        val file = myFixture.configureByText("TriviaNative.cs", text)
        val all = PsiTreeUtil.collectElements(file) { it.node.elementType.let { type -> NativeTokenTypes.COMMENTS.contains(type) || NativeTokenTypes.WHITESPACES.contains(type) } }
        assertFalse(all.isEmpty())
        for (comment in listOf("// line", "/* block", "#if", "#endif")) assertInstanceOf(leafAt(file, text, comment), PsiComment::class.java)
        assertInstanceOf(file.findElementAt(text.indexOf("class") - 1), PsiWhiteSpace::class.java)
        for (element in all) {
            if (NativeTokenTypes.WHITESPACES.contains(element.elementType)) assertInstanceOf(element, PsiWhiteSpace::class.java)
            else assertInstanceOf(element, PsiComment::class.java)
        }
        assertInstanceOf(leafAt(file, text, "<summary>").let { PsiTreeUtil.getParentOfType(it, PsiComment::class.java, false) }, PsiComment::class.java)
        val y = leafAt(file, text, "y;")
        assertTrue(CSharpLeaves.codeLeaf(leafAt(file, text, "int y"), forward = false)!!.textMatches(";"))
        assertTrue(CSharpLeaves.codeLeaf(y, forward = true)!!.textMatches(";"))
        assertTrue(CSharpLeaves.isTrivia(leafAt(file, text, "<summary>")))
        assertTrue(CSharpLeaves.isTrivia(leafAt(file, text, "line")))
    }

    fun testTodoOnBothTrees() {
        val text = "class A\n{\n    // TODO first\n    /* FIXME second */\n    string s = \"TODO not one\";\n}\n"
        onBothTrees("Todo", text) { file, native ->
            assertEquals("native=$native", 2, PsiTodoSearchHelper.getInstance(project).findTodoItems(file).size)
        }
    }

    fun testEfMarkerOnceOnBothTrees() {
        val text = "using Microsoft.EntityFrameworkCore;\npublic class ShopContext : DbContext\n{\n    public ShopContext() : base( { }\n}\n"
        onBothTrees("EfMarker", text) { file, native ->
            val all = PsiTreeUtil.collectElements(file) { true }
            assertEquals("native=$native", 1, all.count { EfLineMarkerProvider().getLineMarkerInfo(it) != null })
        }
    }

    /** The native parser puts zero-width tokens for missing ones and may leave empty nodes: neither carries a marker. */
    fun testZeroWidthElementsAreNoMarkerLeaves() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val file = myFixture.configureByText("ZeroWidthNative.cs", "class A { void M() { int x = 1 } }")
        val empty = PsiTreeUtil.collectElements(file) { it.firstChild == null && it.textLength == 0 }
        assertFalse(empty.isEmpty())
        assertTrue(empty.none(CSharpLeaves::isMarkerLeaf))
        assertTrue(CSharpLeaves.isMarkerLeaf(file.findElementAt(file.text.indexOf("x"))!!))
    }
}
