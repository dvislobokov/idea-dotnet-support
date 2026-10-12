package io.github.dotnetsupport

import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSemanticColors
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The colors of a file are the same whatever happens to the AST of the other files (№ 79 of docs/compare/jellyfin-2026-10-12.md): the
 * platform's idempotence check saw 6308 != 6316 colors on Jellyfin — a name typed through a declaration of another file got its color
 * only while that file's AST happened to be loaded (open, or not yet collected).
 */
class CSharpSemanticColorsIdempotenceTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun recolor(file: CSharpFile): List<String> {
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        return NativeCSharpSemanticColors.colors(file).map { (range, key) -> "${range.substring(file.text)}:${key.externalName}" }
    }

    fun testTheColorsDoNotDependOnTheAstOfAnotherFile() {
        // created on disk only: its PSI comes without an AST until someone reads it
        val vf = myFixture.tempDirFixture.createFile("Idem/Customer.cs", """
            namespace Idem;
            public class IdemCustomer { public IdemAddress Home { get; set; } public IdemAddress Work() => new(); }
            public class IdemAddress { public string City { get; set; } = ""; public static int Count; public void Visit() { } }
        """.trimIndent())
        val other = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as CSharpFile
        val main = myFixture.addFileToProject("Idem/Main.cs", """
            namespace Idem;
            class IdemMain
            {
                void F(IdemCustomer c)
                {
                    var city = c.Home.City;
                    c.Work().Visit();
                }
            }
        """.trimIndent()) as CSharpFile
        assertNull("the AST of the other file is not loaded", (other as PsiFileImpl).treeElement)
        val unloaded = recolor(main)
        other.node // the AST of the other file loaded, as an editor or another feature does
        assertNotNull((other as PsiFileImpl).treeElement)
        val loaded = recolor(main)
        assertEquals(loaded, unloaded)
        // a file open in an editor is read: its declarations type the names (`City` of `c.Home`)
        assertFalse(unloaded.toString(), "City:CSHARP_PROPERTY_IDENTIFIER" in unloaded)
        myFixture.openFileInEditor(vf)
        assertTrue(recolor(main).toString(), "City:CSHARP_PROPERTY_IDENTIFIER" in recolor(main))
    }
}
