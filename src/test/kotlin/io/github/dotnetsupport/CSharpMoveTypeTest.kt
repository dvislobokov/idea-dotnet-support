package io.github.dotnetsupport

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.MoveTypeToFileIntention
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** «Move 'B' to B.cs» leaves in the new file only the `using` directives the type needs, as Rider (Jellyfin № 64). */
class CSharpMoveTypeTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheNewFileKeepsTheUsingsItNeeds() {
        val text = "using System;\nusing System.Collections.Generic;\nusing System.Linq;\n\nnamespace Shop;\n\n" +
            "public class Order\n{\n    public List<int> Lines = new();\n}\n\npublic class Stamp\n{\n    public DateTime At;\n}\n"
        val file = myFixture.addFileToProject("move/Order.cs", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("Stamp"))
        val move = MoveTypeToFileIntention()
        assertTrue(move.isAvailable(project, myFixture.editor, myFixture.file))
        move.invoke(project, myFixture.editor, myFixture.file)
        val created = file.virtualFile.parent.findChild("Stamp.cs")!!
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class Stamp\n{\n    public DateTime At;\n}\n", FileDocumentManager.getInstance().getDocument(created)!!.text)
        assertEquals("using System;\nusing System.Collections.Generic;\nusing System.Linq;\n\nnamespace Shop;\n\npublic class Order\n{\n    public List<int> Lines = new();\n}\n",
            FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.text)
    }

    private fun open(path: String, text: String, anchor: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(anchor))
        return myFixture.file
    }

    private fun run(row: com.intellij.codeInsight.intention.IntentionAction, answer: String) {
        io.github.dotnetsupport.lang.NativeCSharpMoveTypeEdits.answer = answer
        try {
            assertTrue(row.text, row.isAvailable(project, myFixture.editor, myFixture.file))
            row.invoke(project, myFixture.editor, myFixture.file)
        } finally {
            io.github.dotnetsupport.lang.NativeCSharpMoveTypeEdits.answer = null
        }
    }

    private fun text(file: com.intellij.openapi.vfs.VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    /** Extract Members to Partial (Jellyfin № 57, as Rider): `Order.Persistence.cs` with the member and its doc comment, both parts `partial`. */
    fun testExtractMembersToPartial() {
        val source = open("partial/Order.cs", "using System;\nusing System.Collections.Generic;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n\n    /// <summary>Saves.</summary>\n    public void Save()\n    {\n        Console.WriteLine(Id);\n    }\n}\n", "Save()")
        run(io.github.dotnetsupport.lang.NativeCSharpExtractToPartialRow(), "Persistence")
        val part = source.virtualFile.parent.findChild("Order.Persistence.cs")!!
        assertEquals("using System;\n\nnamespace Shop;\n\npublic partial class Order\n{\n    /// <summary>Saves.</summary>\n    public void Save()\n    {\n        Console.WriteLine(Id);\n    }\n}\n", text(part))
        assertEquals("using System;\nusing System.Collections.Generic;\n\nnamespace Shop;\n\npublic partial class Order\n{\n    public int Id;\n}\n", text(source.virtualFile))
        // a block namespace and a generic type: the part without `sealed`, the type parameters kept
        val block = open("partial2/Line.cs", "namespace Shop\n{\n    internal sealed class Line<T>\n    {\n        public T Value;\n        public int Count() => 1;\n    }\n}\n", "Count()")
        run(io.github.dotnetsupport.lang.NativeCSharpExtractToPartialRow(), "Counting")
        assertEquals("namespace Shop\n{\n    internal partial class Line<T>\n    {\n        public int Count() => 1;\n    }\n}\n", text(block.virtualFile.parent.findChild("Line.Counting.cs")!!))
        assertEquals("namespace Shop\n{\n    internal sealed partial class Line<T>\n    {\n        public T Value;\n    }\n}\n", text(block.virtualFile))
        // not on a type header
        open("partial3/Order.cs", "using System;\nusing System.Collections.Generic;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n\n    /// <summary>Saves.</summary>\n    public void Save()\n    {\n        Console.WriteLine(Id);\n    }\n}\n", "class Order")
        assertFalse(io.github.dotnetsupport.lang.NativeCSharpExtractToPartialRow().isAvailable(project, myFixture.editor, myFixture.file))
    }

    /** Move Type to Another Namespace (Jellyfin № 54): the declaration renamed, `using` added where the type is used by its name. */
    fun testMoveTypeToAnotherNamespace() {
        val page = myFixture.addFileToProject("ns/Page.cs", "using System;\n\nnamespace Shop.Web;\n\npublic class Page\n{\n    public Shop.Models.Stamp? Unused;\n    public Stamp? Field;\n}\n")
        val other = myFixture.addFileToProject("ns/Other.cs", "namespace Shop.Models.Extra;\n\npublic class Other\n{\n    public Stamp? Field;\n}\n")
        val stamp = open("ns/Stamp.cs", "namespace Shop.Models;\n\npublic class Stamp\n{\n}\n", "Stamp")
        // Other.cs (in Shop.Models.Extra) sees Stamp through its outer namespace: it gets `using Shop.Domain;`; Page.cs names it qualified only
        run(io.github.dotnetsupport.lang.NativeCSharpMoveToNamespaceRow(), "Shop.Domain")
        assertTrue(text(stamp.virtualFile), text(stamp.virtualFile).startsWith("namespace Shop.Domain;"))
        assertTrue(text(other.virtualFile), text(other.virtualFile).contains("using Shop.Domain;"))
        // two types in a file: not offered
        open("ns2/Two.cs", "namespace A;\n\npublic class B { }\npublic class C { }\n", "class B")
        assertFalse(io.github.dotnetsupport.lang.NativeCSharpMoveToNamespaceRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertNotNull(page)
    }

    /** Move to Another File: a type of a file with several into the file named; a lone type renames its file. */
    fun testMoveToAnotherFile() {
        val two = open("other/Two.cs", "namespace A;\n\npublic class B { }\n\npublic class C { }\n", "class C")
        run(io.github.dotnetsupport.lang.NativeCSharpMoveToAnotherFileRow(), "Cee")
        assertEquals("namespace A;\n\npublic class C { }\n", text(two.virtualFile.parent.findChild("Cee.cs")!!))
        assertEquals("namespace A;\n\npublic class B { }\n", text(two.virtualFile))
        val lone = open("other2/Misnamed.cs", "namespace A;\n\npublic class D { }\n", "class D")
        run(io.github.dotnetsupport.lang.NativeCSharpMoveToAnotherFileRow(), "D.cs")
        assertEquals("D.cs", lone.virtualFile.name)
    }

    /** Move to Folder: the file of a lone type into a folder of the project. */
    fun testMoveToFolder() {
        val file = open("folder/App/Order.cs", "namespace App;\n\npublic class Order { }\n", "class Order")
        run(io.github.dotnetsupport.lang.NativeCSharpMoveToFolderRow(), "Models")
        assertEquals("Models", file.virtualFile.parent.name)
    }

    /** The rows are in Refactor This where Rider has them: the moves on a type header, Extract Members to Partial on a member. */
    fun testTheRowsOfRefactorThis() {
        open("rows/Order.cs", "using System;\nusing System.Collections.Generic;\n\nnamespace Shop;\n\npublic class Order\n{\n    public int Id;\n\n    /// <summary>Saves.</summary>\n    public void Save()\n    {\n        Console.WriteLine(Id);\n    }\n}\n", "class Order")
        fun rows() = io.github.dotnetsupport.lang.CSharpRiderPopups.nativeActions(io.github.dotnetsupport.lang.CSharpPopupKind.REFACTOR, project, myFixture.editor, myFixture.file)
            .mapNotNull { it.templatePresentation.text }
        assertTrue(rows().toString(), rows().containsAll(listOf("Move Type to Another Namespace", "Move to Another File", "Move to Folder")))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("Save()"))
        assertTrue(rows().toString(), "Extract Members to Partial" in rows() && "Move to Another File" !in rows())
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpMoveTypeTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
