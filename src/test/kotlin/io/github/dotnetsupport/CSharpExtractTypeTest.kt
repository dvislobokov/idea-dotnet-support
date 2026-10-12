package io.github.dotnetsupport

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpExtractClassRow
import io.github.dotnetsupport.lang.NativeCSharpExtractInterfaceRow
import io.github.dotnetsupport.lang.NativeCSharpExtractSuperclassRow
import io.github.dotnetsupport.lang.NativeCSharpExtractTypeEdits
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** Refactor This: Extract Interface / Superclass / Class (Jellyfin № 52, as Rider), driven without the dialog. */
class CSharpExtractTypeTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpExtractTypeEdits.answer = null
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun open(text: String, anchor: String): PsiFile {
        val file = myFixture.addFileToProject("extract${files++}/Source.cs", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(anchor))
        return myFixture.file
    }

    /** Runs [row] choosing the members named [members] (and [abstract] ones), [name], in a new file or not. */
    private fun run(row: IntentionAction, name: String, members: List<String>, abstract: List<String> = emptyList(), newFile: Boolean = true) {
        NativeCSharpExtractTypeEdits.answer = { _, listed, _ ->
            NativeCSharpExtractTypeEdits.Choice(name, listed.filter { it.name in members }.map { it.element }, listed.filter { it.name in abstract }.map { it.element }.toSet(), newFile)
        }
        assertTrue(row.text, row.isAvailable(project, myFixture.editor, myFixture.file))
        row.invoke(project, myFixture.editor, myFixture.file)
    }

    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    fun testExtractInterfaceOfMethodsPropertiesAndAnEvent() {
        val source = open("using System;\n\nnamespace Shop;\n\npublic class OrderService : IDisposable\n{\n    /// <summary>Saves.</summary>\n" +
            "    public void Save(int id) { }\n    public string Name { get; private set; } = \"\";\n    public int Count => 1;\n" +
            "    public event EventHandler? Changed;\n    private void Helper() { }\n    public void Dispose() { }\n}\n", "OrderService")
        run(NativeCSharpExtractInterfaceRow(), "IOrderService", listOf("Save", "Name", "Count", "Changed"))
        assertEquals("using System;\n\nnamespace Shop;\n\npublic class OrderService : IDisposable, IOrderService\n{\n    /// <summary>Saves.</summary>\n" +
            "    public void Save(int id) { }\n    public string Name { get; private set; } = \"\";\n    public int Count => 1;\n" +
            "    public event EventHandler? Changed;\n    private void Helper() { }\n    public void Dispose() { }\n}\n", text(source.virtualFile))
        assertEquals("using System;\n\nnamespace Shop;\n\npublic interface IOrderService\n{\n    /// <summary>Saves.</summary>\n    void Save(int id);\n\n" +
            "    string Name { get; }\n\n    int Count { get; }\n\n    event EventHandler? Changed;\n}\n", text(source.virtualFile.parent.findChild("IOrderService.cs")!!))
        // a private member is no candidate
        assertTrue(NativeCSharpExtractTypeEdits.candidates(NativeCSharpExtractTypeEdits.Kind.INTERFACE,
            NativeCSharpExtractTypeEdits.ownerAt(myFixture.file as io.github.dotnetsupport.lang.CSharpFile, myFixture.caretOffset)!!).none { it.name == "Helper" })
    }

    fun testExtractInterfaceOfAGenericClassInTheSameFile() {
        val source = open("namespace Shop\n{\n    public class Store<T, TKey> where T : class\n    {\n        public T Find(TKey key) => default!;\n        public int Size() => 0;\n    }\n}\n", "Find")
        run(NativeCSharpExtractInterfaceRow(), "IStore", listOf("Find"), newFile = false)
        assertEquals("namespace Shop\n{\n    public class Store<T, TKey> : IStore<T, TKey> where T : class\n    {\n        public T Find(TKey key) => default!;\n" +
            "        public int Size() => 0;\n    }\n\n    public interface IStore<T, TKey> where T : class\n    {\n        T Find(TKey key);\n    }\n}\n", text(source.virtualFile))
    }

    fun testExtractSuperclassMovesAndAbstractsMembersAndProtectsThePrivateHelper() {
        val source = open("namespace Shop;\n\npublic class Report : IReport\n{\n    private int _count;\n\n    public void Add() => _count = Bump(_count);\n\n" +
            "    public string Title() => \"r\";\n\n    private static int Bump(int x) => x + 1;\n\n    public int Total() => _count;\n}\n\npublic interface IReport { }\n", "Report :")
        run(NativeCSharpExtractSuperclassRow(), "ReportBase", listOf("_count", "Add", "Title"), abstract = listOf("Title"))
        assertEquals("namespace Shop;\n\npublic abstract class ReportBase\n{\n    protected int _count;\n\n    public void Add() => _count = Bump(_count);\n\n" +
            "    public abstract string Title();\n}\n", text(source.virtualFile.parent.findChild("ReportBase.cs")!!))
        assertEquals("namespace Shop;\n\npublic class Report : ReportBase, IReport\n{\n    public override string Title() => \"r\";\n\n" +
            "    protected static int Bump(int x) => x + 1;\n\n    public int Total() => _count;\n}\n\npublic interface IReport { }\n", text(source.virtualFile))
    }

    fun testExtractSuperclassKeepsTheOldBaseClass() {
        val source = open("namespace Shop;\n\npublic class Animal { }\n\npublic class Dog : Animal\n{\n    public void Bark() { }\n}\n", "Dog :")
        run(NativeCSharpExtractSuperclassRow(), "Pet", listOf("Bark"), newFile = false)
        assertEquals("namespace Shop;\n\npublic class Animal { }\n\npublic class Dog : Pet\n{\n}\n\npublic class Pet : Animal\n{\n    public void Bark() { }\n}\n", text(source.virtualFile))
    }

    fun testExtractClassMovesFieldsAndAMethodAndRoutesTheUses() {
        val source = open("namespace Shop;\n\npublic class Cart\n{\n    private int _count;\n    private decimal _sum;\n\n" +
            "    private void Add(decimal price)\n    {\n        _count++;\n        _sum += price;\n    }\n\n" +
            "    public void Buy(decimal price)\n    {\n        Add(price);\n        System.Console.WriteLine(this._sum);\n    }\n}\n", "Cart")
        run(NativeCSharpExtractClassRow(), "Totals", listOf("_count", "_sum", "Add"))
        assertEquals("namespace Shop;\n\npublic class Totals\n{\n    public int _count;\n\n    public decimal _sum;\n\n" +
            "    public void Add(decimal price)\n    {\n        _count++;\n        _sum += price;\n    }\n}\n", text(source.virtualFile.parent.findChild("Totals.cs")!!))
        assertEquals("namespace Shop;\n\npublic class Cart\n{\n    private readonly Totals _totals = new();\n\n" +
            "    public void Buy(decimal price)\n    {\n        _totals.Add(price);\n        System.Console.WriteLine(_totals._sum);\n    }\n}\n", text(source.virtualFile))
    }

    fun testExtractClassIsRefusedWhenAMemberUsesTheRest() {
        open("namespace Shop;\n\npublic class Cart\n{\n    private int _count;\n    private int _limit;\n\n    private bool Full() => _count >= _limit;\n}\n", "Cart")
        val owner = NativeCSharpExtractTypeEdits.ownerAt(myFixture.file as io.github.dotnetsupport.lang.CSharpFile, myFixture.caretOffset)!!
        val members = NativeCSharpExtractTypeEdits.candidates(NativeCSharpExtractTypeEdits.Kind.CLASS, owner)
        val choice = NativeCSharpExtractTypeEdits.Choice("Data", members.filter { it.name == "_count" || it.name == "Full" }.map { it.element })
        assertNull(NativeCSharpExtractTypeEdits.extractClass(myFixture.editor.document.immutableCharSequence, owner, choice, "    "))
    }

    fun testNotOfferedOnAnInterfaceOrAStaticClass() {
        open("namespace Shop;\n\npublic interface IShape\n{\n    void Draw();\n}\n", "IShape")
        assertFalse(NativeCSharpExtractInterfaceRow().isAvailable(project, myFixture.editor, myFixture.file))
        open("namespace Shop;\n\npublic static class Tools\n{\n    public static void Run() { }\n}\n", "Tools")
        assertFalse(NativeCSharpExtractInterfaceRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertFalse(NativeCSharpExtractSuperclassRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertFalse(NativeCSharpExtractClassRow().isAvailable(project, myFixture.editor, myFixture.file))
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpExtractTypeTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
