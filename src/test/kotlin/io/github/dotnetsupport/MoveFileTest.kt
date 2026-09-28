package io.github.dotnetsupport

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpMoveFileHandler
import io.github.dotnetsupport.lang.CSharpNamespaceRename
import io.github.dotnetsupport.lang.CSharpNamespaceSync

/** A `.cs` file moved to another folder gets the namespace of the folder. */
class MoveFileTest : BasePlatformTestCase() {
    fun testNamespaceRenameInText() {
        assertEquals("namespace Shop.Models;\n\npublic class A { }\n", CSharpNamespaceRename.rename("namespace Shop;\n\npublic class A { }\n", "Shop", "Shop.Models"))
        assertEquals("namespace Shop.Models\n{\n    class A { }\n}\n", CSharpNamespaceRename.rename("namespace Shop\n{\n    class A { }\n}\n", "Shop", "Shop.Models"))
        assertNull("another namespace is not touched", CSharpNamespaceRename.rename("namespace Other;\nclass A { }", "Shop", "Shop.Models"))
        assertNull("no namespace at all", CSharpNamespaceRename.rename("class A { }", "Shop", "Shop.Models"))
        assertEquals("Shop", CSharpNamespaceSync.declaredNamespace("using System;\nnamespace Shop;\nclass A { }"))
        assertNull("two namespaces: nothing is guessed", CSharpNamespaceSync.declaredNamespace("namespace A { }\nnamespace B { }"))
    }

    fun testMoveChangesTheNamespaceOfTheFile() {
        myFixture.addFileToProject("Shop/Shop.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><RootNamespace>Shop</RootNamespace></PropertyGroup></Project>")
        val file = myFixture.addFileToProject("Shop/Order.cs", "namespace Shop;\n\npublic class Order { }\n")
        val models = myFixture.addFileToProject("Shop/Models/keep.txt", "").containingDirectory!!
        assertTrue(MoveFileHandler.forElement(file) is CSharpMoveFileHandler)
        assertNull(CSharpNamespaceSync.mismatch(project, file.virtualFile))

        CSharpNamespaceSync.askBeforeAdjusting = false
        MoveFilesOrDirectoriesProcessor(project, arrayOf(file), models, false, false, null, null).run()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        // no language server in tests: the file alone is changed, after the write action of the move
        val moved = PsiManager.getInstance(project).findDirectory(models.virtualFile)!!.findFile("Order.cs")!!
        val text = FileDocumentManager.getInstance().getDocument(moved.virtualFile)!!.text
        assertEquals("namespace Shop.Models;\n\npublic class Order { }\n", text)
        assertNull(CSharpNamespaceSync.mismatch(project, moved.virtualFile))
    }
}
