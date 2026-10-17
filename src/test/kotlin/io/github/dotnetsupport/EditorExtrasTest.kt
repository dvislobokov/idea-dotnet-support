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
        // the old format has no project.assets.json at all: packages.config is restored into ..\packages
        myFixture.addFileToProject("bn/Legacy/Legacy.csproj", """<Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003"><ItemGroup><Compile Include="Program.cs" /></ItemGroup></Project>""")
        val legacy = myFixture.addFileToProject("bn/Legacy/Program.cs", "class Program { }").virtualFile

        fun banner(file: com.intellij.openapi.vfs.VirtualFile) = CSharpEditorBanners.bannerFor(project, file)
        assertEquals(CSharpEditorBanners.Kind.NO_PROJECT, banner(loose)?.kind)
        assertEquals(CSharpEditorBanners.Kind.EXCLUDED, banner(excluded)?.kind)
        assertEquals(CSharpEditorBanners.Kind.NOT_RESTORED, banner(notRestored)?.kind)
        assertNull(banner(fine))
        assertNull("a project of the old format", banner(legacy))
        assertNull("not a C# file", CSharpEditorBanners.bannerFor(project, myFixture.addFileToProject("bn/loose/readme.md", "").virtualFile))
    }

    fun testIntentionsAreRegisteredForCSharp() {
        // the registrations are wrappers around the classes
        val all = IntentionManager.getInstance().intentionActions.map { com.intellij.codeInsight.intention.IntentionActionDelegate.unwrap(it).javaClass.simpleName }
        for (name in listOf("AdjustNamespaceIntention", "MoveTypeToFileIntention", "RenameFileToTypeIntention", "AddPartialPartIntention", "CreateTestIntention")) {
            assertTrue(name, name in all)
        }
    }

    /** The new file and the old one clean, as Rider's (Jellyfin № 64): no blank line after `{`, the indentation of what follows kept. */
    fun testMoveTypeToFileSplitsTheFile() {
        val text = "using System;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n}\n\npublic class OrderLine\n{\n}\n"
        val split = CSharpFileLayout.split(text, text.indexOf("OrderLine"))!!
        assertEquals("OrderLine", split.typeName)
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class OrderLine\n{\n}\n", split.newFileText)
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n}\n", split.remainingText)

        val block = "namespace Shop\n{\n    class A { }\n    class B { }\n}\n"
        val blockSplit = CSharpFileLayout.split(block, block.indexOf("class B"))!!
        assertEquals("namespace Shop\n{\n    class B { }\n}\n", blockSplit.newFileText)
        assertEquals("namespace Shop\n{\n    class A { }\n}\n", blockSplit.remainingText)
        assertNull("one type: nothing to move", CSharpFileLayout.split("class A { }", 3))

        // Jellyfin SeriesTimerInfoDto.cs: the first type of a block namespace, the doc comment of the next one keeps its indentation
        val jellyfin = "#nullable disable\n#pragma warning disable CS1591\n\nusing System;\nusing System.Collections.Generic;\n\nnamespace MediaBrowser.Model.LiveTv\n{\n    public enum KeepUntil\n    {\n        UntilDeleted,\n        UntilDate\n    }\n\n    /// <summary>\n    /// Class SeriesTimerInfoDto.\n    /// </summary>\n    public class SeriesTimerInfoDto\n    {\n    }\n}\n"
        val keep = CSharpFileLayout.split(jellyfin, jellyfin.indexOf("KeepUntil"))!!
        assertEquals("#nullable disable\n#pragma warning disable CS1591\n\nusing System;\nusing System.Collections.Generic;\n\nnamespace MediaBrowser.Model.LiveTv\n{\n    public enum KeepUntil\n    {\n        UntilDeleted,\n        UntilDate\n    }\n}\n", keep.newFileText)
        assertEquals("#nullable disable\n#pragma warning disable CS1591\n\nusing System;\nusing System.Collections.Generic;\n\nnamespace MediaBrowser.Model.LiveTv\n{\n    /// <summary>\n    /// Class SeriesTimerInfoDto.\n    /// </summary>\n    public class SeriesTimerInfoDto\n    {\n    }\n}\n", keep.remainingText)
        // `csharp_style_namespace_declarations = file_scoped`: the new file file-scoped, the type one level left
        assertEquals("#nullable disable\n#pragma warning disable CS1591\n\nusing System;\nusing System.Collections.Generic;\n\nnamespace MediaBrowser.Model.LiveTv;\n\npublic enum KeepUntil\n{\n    UntilDeleted,\n    UntilDate\n}\n", CSharpFileLayout.split(jellyfin, jellyfin.indexOf("KeepUntil"), fileScoped = true)!!.newFileText)

        // the doc comment and the attributes go with the type
        val documented = "namespace Shop;\n\npublic class A { }\n\n/// <summary>The line.</summary>\n[Serializable]\npublic class B { }\n"
        val doc = CSharpFileLayout.split(documented, documented.indexOf("class B"))!!
        assertEquals("namespace Shop;\n\n/// <summary>The line.</summary>\n[Serializable]\npublic class B { }\n", doc.newFileText)
        assertEquals("namespace Shop;\n\npublic class A { }\n", doc.remainingText)
    }

    /** «Add a partial part» only on the header of the type, not in every body (Jellyfin № 21, as Rider). */
    fun testPartialPartOnlyOnTheHeaderOfTheType() {
        io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(true)
        try {
            val text = "namespace App;\n\n[Serializable]\npublic class Order : Base\n{\n    public int Id { get; set; }\n    public void Save()\n    {\n        var x = 1;\n    }\n}\n"
            myFixture.configureByText("Header.cs", text)
            fun at(anchor: String): Boolean {
                myFixture.editor.caretModel.moveToOffset(text.indexOf(anchor) + 1)
                return AddPartialPartIntention().isAvailable(project, myFixture.editor, myFixture.file)
            }
            assertTrue("the name", at("Order"))
            assertTrue("the modifiers", at("public class"))
            assertTrue("the base list", at("Base"))
            assertFalse("a property", at("Id {"))
            assertFalse("a method header", at("Save()"))
            assertFalse("a body", at("var x"))
        } finally {
            io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
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

    fun testGhostTextProviderIsRegistered() {
        val providers = com.intellij.codeInsight.inline.completion.InlineCompletionProvider.EP_NAME.extensionList.map { it.javaClass.simpleName }
        assertTrue(providers.toString(), "CSharpGhostTextProvider" in providers)
    }

    fun testEditorConfigDotNetSupportIsSwitchedOnWhenThePluginIsThere() {
        val on = EditorConfigDotNetSupport.enable()
        if (on) assertTrue(com.intellij.openapi.util.registry.Registry.`is`(EditorConfigDotNetSupport.DOTNET_KEY))
    }
}
