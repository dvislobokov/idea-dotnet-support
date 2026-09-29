package io.github.dotnetsupport

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.AddPartialPartIntention
import io.github.dotnetsupport.lang.AdjustNamespaceIntention
import io.github.dotnetsupport.lang.CSharpEditorBanners
import io.github.dotnetsupport.lang.CSharpFileLayout
import io.github.dotnetsupport.lang.EditorConfigDotNetSupport
import io.github.dotnetsupport.lang.MoveTypeToFileIntention
import io.github.dotnetsupport.lang.RenameFileToTypeIntention

/** Banners above a C# file, the file-level intentions, the .NET options of .editorconfig. */
class EditorExtrasTest : BasePlatformTestCase() {
    fun testBannersOfAFile() {
        val loose = myFixture.addFileToProject("bn/loose/Program.cs", "class Program { }").virtualFile
        myFixture.addFileToProject("bn/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><Compile Remove="Old\**" /><None Remove="Old\**" /></ItemGroup></Project>""")
        val excluded = myFixture.addFileToProject("bn/App/Old/Gone.cs", "class Gone { }").virtualFile
        val notRestored = myFixture.addFileToProject("bn/App/Program.cs", "class Program { }").virtualFile
        myFixture.addFileToProject("bn/Ok/Ok.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        myFixture.addFileToProject("bn/Ok/obj/project.assets.json", "{}")
        val fine = myFixture.addFileToProject("bn/Ok/Program.cs", "class Program { }").virtualFile

        // the machine may lack the server: that banner is the first one and would hide the others, so it is skipped when present
        fun banner(file: com.intellij.openapi.vfs.VirtualFile) = CSharpEditorBanners.bannerFor(project, file)?.takeIf { it.kind != CSharpEditorBanners.Kind.NO_SERVER }
        val serverMissing = CSharpEditorBanners.bannerFor(project, fine)?.kind == CSharpEditorBanners.Kind.NO_SERVER
        if (!serverMissing) {
            assertEquals(CSharpEditorBanners.Kind.NO_PROJECT, banner(loose)?.kind)
            assertEquals(CSharpEditorBanners.Kind.EXCLUDED, banner(excluded)?.kind)
            assertEquals(CSharpEditorBanners.Kind.NOT_RESTORED, banner(notRestored)?.kind)
            assertNull(banner(fine))
            assertNull("not a C# file", CSharpEditorBanners.bannerFor(project, myFixture.addFileToProject("bn/loose/readme.md", "").virtualFile))
        }
    }

    fun testIntentionsAreRegisteredForCSharp() {
        // the registrations are wrappers around the classes
        val all = IntentionManager.getInstance().intentionActions.map { com.intellij.codeInsight.intention.IntentionActionDelegate.unwrap(it).javaClass.simpleName }
        for (name in listOf("AdjustNamespaceIntention", "MoveTypeToFileIntention", "RenameFileToTypeIntention", "AddPartialPartIntention", "CreateTestIntention")) {
            assertTrue(name, name in all)
        }
    }

    fun testMoveTypeToFileSplitsTheFile() {
        val text = "using System;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n}\n\npublic class OrderLine\n{\n}\n"
        val split = CSharpFileLayout.split(text, text.indexOf("OrderLine"))!!
        assertEquals("OrderLine", split.typeName)
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class OrderLine\n{\n}\n", split.newFileText)
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n}\n\n", split.remainingText)

        val block = "namespace Shop\n{\n    class A { }\n    class B { }\n}\n"
        val blockSplit = CSharpFileLayout.split(block, block.indexOf("class B"))!!
        assertEquals("namespace Shop\n{\n\n    class B { }\n}\n", blockSplit.newFileText)
        assertNull("one type: nothing to move", CSharpFileLayout.split("class A { }", 3))
    }

    fun testFileIntentionsAvailability() {
        myFixture.addFileToProject("it/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><RootNamespace>App</RootNamespace></PropertyGroup></Project>""")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("it/App/Models/Order.cs", "namespace App;\n\npublic class Or<caret>der { }\npublic class Line { }\n").virtualFile)
        val adjust = AdjustNamespaceIntention()
        assertTrue(adjust.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Change namespace to 'App.Models'", adjust.text)
        val move = MoveTypeToFileIntention()
        assertFalse("the file's own type stays", move.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("Line"))
        assertTrue(move.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Move 'Line' to Line.cs", move.text)
        assertFalse("two types: no rename after one", RenameFileToTypeIntention().isAvailable(project, myFixture.editor, myFixture.file))
        assertTrue(AddPartialPartIntention().isAvailable(project, myFixture.editor, myFixture.file))

        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("it/App/Models/Misnamed.cs", "namespace App.Models;\n\npublic class Invoice { }\n").virtualFile)
        val rename = RenameFileToTypeIntention()
        assertTrue(rename.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Rename file to 'Invoice.cs'", rename.text)
        rename.invoke(project, myFixture.editor, myFixture.file)
        assertEquals("Invoice.cs", myFixture.file.virtualFile.name)
    }

    fun testEditorConfigDotNetSupportIsSwitchedOnWhenThePluginIsThere() {
        val on = EditorConfigDotNetSupport.enable()
        if (on) assertTrue(com.intellij.openapi.util.registry.Registry.`is`(EditorConfigDotNetSupport.DOTNET_KEY))
    }
}
