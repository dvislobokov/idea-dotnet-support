package io.github.dotnetsupport

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpClassDeclaration
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.HeuristicCSharpFile

/** The registered parser definition of C# switches between the heuristic tree and csharp-psi's (CSHARP_PSI_MIGRATION.md, step 7). */
class CSharpParserSwitchTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    /** The default since 0.1.45: `CSharpFeature.SYNTAX_TREE` is NATIVE until the user stores ROSLYN. */
    fun testTheNativeTreeIsTheDefault() {
        assertTrue(CSharpSyntaxTrees.nativeTree())
        val file = myFixture.configureByText("ParserSwitchDefault.cs", "namespace N { class A { void M() { } } }")
        assertFalse(file is HeuristicCSharpFile)
        assertNotNull((file as CSharpFile).compilationUnit)
    }

    fun testTheHeuristicTreeWhenForced() {
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        val file = myFixture.configureByText("ParserSwitchHeuristic.cs", "namespace N { class A { void M() { } } }")
        assertInstanceOf(file, HeuristicCSharpFile::class.java)
        assertTrue(file is CSharpFile)
        assertNull((file as CSharpFile).compilationUnit)
        assertSame(CSharpFileType, file.fileType)
        assertFalse(PsiTreeUtil.findChildrenOfType(file, CSharpDeclaration::class.java).isEmpty())
    }

    fun testTheNativeTreeWhenForced() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val file = myFixture.configureByText("ParserSwitchNative.cs", "namespace N { class A { void M() { } } }")
        assertTrue(file is CSharpFile)
        assertFalse(file is HeuristicCSharpFile)
        val unit = (file as CSharpFile).compilationUnit
        assertNotNull(unit)
        assertSame(CSharpFileType, file.fileType)
        assertEquals("A", PsiTreeUtil.findChildOfType(file, CSharpClassDeclaration::class.java)?.identifier?.text)
        assertTrue(PsiTreeUtil.findChildrenOfType(file, CSharpDeclaration::class.java).isEmpty())
        // the text survives the parse, comments and whitespace included
        assertEquals(file.text, file.node.text)
    }

    /** PSI goes by the element type, not by the switch: a tree built under one setting stays right after the switch moves. */
    fun testPsiOfAnExistingTreeDoesNotFollowTheSwitch() {
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        val heuristic = myFixture.configureByText("ParserSwitchKeep.cs", "class B { }")
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        assertFalse(PsiTreeUtil.findChildrenOfType(heuristic, CSharpDeclaration::class.java).isEmpty())

        val native = myFixture.configureByText("ParserSwitchKeepNative.cs", "class C { }")
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        assertEquals("C", PsiTreeUtil.findChildOfType(native, CSharpClassDeclaration::class.java)?.identifier?.text)
    }
}
