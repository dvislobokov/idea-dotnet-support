package io.github.dotnetsupport.csharp.lang.psi.stubs

import com.intellij.lang.ASTNode
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.SerializationManagerEx
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubTextInconsistencyException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpStubElementImpl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Stubs of the native tree (CSHARP_PSI_MIGRATION.md, step 8; docs/csharp-psi/GRAMMAR.md, "Stubs"): what is stubbed per declaration kind (a
 * golden that also guards [CSharpStubs.VERSION]), stubs equal to what the PSI says, the indexes answered without loading the AST, serialization,
 * edits, the file's `#if` symbols in the indexer.
 */
class CSharpStubTest : BasePlatformTestCase() {
    private val sample = """
        using System;
        [assembly: Foo]
        namespace Shop.Orders
        {
            [Serializable] public sealed partial class Order<T, U> : Base<T>, IOrder where T : class
            {
                public const int Max = 10, Min = 1;
                private int _count;
                public event EventHandler Changed;
                public event EventHandler A, B;
                public Order(int count) { _count = count; int local = 0; void Local() { } }
                ~Order() { }
                public static Order<T, U> operator +(Order<T, U> a, Order<T, U> b) => a;
                public static implicit operator int(Order<T, U> o) => 0;
                public int Count { get; set; }
                public int this[int i] => i;
                public event Action E { add { } remove { } }
                void IOrder.Ship() { }
                [Fact, Xunit.Trait("a", "b")]
                public async Task<int> RunAsync<V>(V   value,
                    int other) { return 0; }
                public class Nested { [global::NUnit.Framework.TestAttribute] void Check() { } }
                public delegate void Handler(object sender);
                extension(string s) { public bool IsEmpty => s.Length == 0; }
            }
            public static class Extensions { public static int Twice(this int x) => x * 2; }
            public enum Color { Red, [Obsolete] Green }
            public interface IOrder { void Ship(); }
            namespace Inner { [TestFixture] public record Point(int X, int Y); public record struct Size(int W); struct S { } }
        }
        class { void Lost() { } }
        void TopLevel() { }
    """.trimIndent() + "\n"

    private val fileScoped = """
        namespace Shop.Items;
        public class Item { public string Name { get; } }
        int Field;
    """.trimIndent() + "\n"

    /** The stub trees of the samples, against `testData/stubs/stubs.txt`; a change of them needs a new [CSharpStubs.VERSION]. */
    fun testStubTreesAndVersion() {
        val dump = "# CSharpStubs.VERSION = ${CSharpStubs.VERSION}\n" + listOf(sample, fileScoped).joinToString("\n") { text ->
            DebugUtil.stubTreeToString(CSharpStubDefinition().builder.buildStubTree(myFixture.configureByText("Dump.cs", text))!!)
        }
        val golden = File(CSharpTestUtil.testDataPath("stubs/stubs.txt"))
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(dump)
            fail("${golden.path}: recorded, review it and run again")
        }
        val expected = golden.readText().replace("\r\n", "\n")
        if (expected != dump) {
            val sameVersion = expected.lineSequence().first() == dump.lineSequence().first()
            assertEquals(if (sameVersion) "the stubs changed: bump CSharpStubs.VERSION, delete ${golden.path} and run again" else "re-record ${golden.path}", expected, dump)
        }
    }

    /** The stubs of a file read from the index and the PSI made from them agree with the AST loaded afterwards: names, parents, identity. */
    fun testStubsAgreeWithThePsi() {
        val file = indexed("Agree.cs", sample)
        val stubTree = (file as PsiFileImpl).stubTree!!
        assertFalse(file.isContentsLoaded)
        val fromStubs = stubTree.plainList.drop(1).map { stub -> stub.psi as CSharpStubElementImpl }
        val names = fromStubs.map { it.name }
        val parents = fromStubs.map { it.parent }
        assertFalse("the stub tree alone answered", file.isContentsLoaded)

        val spine = ArrayList<ASTNode>()
        fun walk(node: ASTNode) {
            if (node.treeParent != null && CSharpStubRules.isStubbed(node)) spine += node
            if (node.treeParent == null || CSharpStubRules.isStubbed(node)) node.getChildren(null).forEach(::walk)
        }
        walk(file.node)
        assertEquals("a stub per stubbed node, in order", fromStubs.map { it.node.elementType }, spine.map { it.elementType })
        assertEquals("the AST was bound to the PSI of the stubs", fromStubs, spine.map { it.psi })
        assertEquals(spine.map { CSharpDeclarationNames.name(it.psi) }, names)
        assertEquals(spine.map { it.treeParent.psi }, parents)
        StubTextInconsistencyException.checkStubTextConsistency(file)
    }

    /** Go to Class / Symbol, extension methods and test attributes from the indexes: no AST loaded. */
    fun testIndexesWithoutAst() {
        val file = indexed("Lookup.cs", sample)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, testRootDisposable)
        fun names(key: StubIndexKey<String, CSharpElement>, name: String) =
            StubIndex.getElements(key, name, project, GlobalSearchScope.allScope(project), CSharpElement::class.java).map { it.toString() + ":" + (it as NavigationItem).name }

        assertEquals(listOf("CSharpClassDeclarationImpl(ClassDeclaration):Order"), names(CSharpStubIndexKeys.TYPE_NAMES, "Order"))
        assertEquals(listOf("CSharpRecordDeclarationImpl(RecordStructDeclaration):Size"), names(CSharpStubIndexKeys.TYPE_NAMES, "Size"))
        assertEquals(listOf("CSharpDelegateDeclarationImpl(DelegateDeclaration):Handler"), names(CSharpStubIndexKeys.TYPE_NAMES, "Handler"))
        assertEquals(emptyList<String>(), names(CSharpStubIndexKeys.TYPE_NAMES, "Shop.Orders"))
        // constructors are named after their type; a field of several declarators by each of them, of one by itself
        assertEquals(listOf("CSharpConstructorDeclarationImpl(ConstructorDeclaration):Order"), names(CSharpStubIndexKeys.MEMBER_NAMES, "Order"))
        assertEquals(listOf("CSharpVariableDeclaratorImpl(VariableDeclarator):Min"), names(CSharpStubIndexKeys.MEMBER_NAMES, "Min"))
        assertEquals(listOf("CSharpFieldDeclarationImpl(FieldDeclaration):_count"), names(CSharpStubIndexKeys.MEMBER_NAMES, "_count"))
        assertEquals(listOf("CSharpEventFieldDeclarationImpl(EventFieldDeclaration):Changed"), names(CSharpStubIndexKeys.MEMBER_NAMES, "Changed"))
        assertEquals(listOf("CSharpVariableDeclaratorImpl(VariableDeclarator):B"), names(CSharpStubIndexKeys.MEMBER_NAMES, "B"))
        assertEquals(listOf("CSharpEnumMemberDeclarationImpl(EnumMemberDeclaration):Green"), names(CSharpStubIndexKeys.MEMBER_NAMES, "Green"))
        assertEquals(listOf("CSharpPropertyDeclarationImpl(PropertyDeclaration):IsEmpty"), names(CSharpStubIndexKeys.MEMBER_NAMES, "IsEmpty"))
        assertEquals(listOf("CSharpOperatorDeclarationImpl(OperatorDeclaration):operator +"), names(CSharpStubIndexKeys.MEMBER_NAMES, "operator +"))
        assertEquals(listOf("CSharpConversionOperatorDeclarationImpl(ConversionOperatorDeclaration):operator int"), names(CSharpStubIndexKeys.MEMBER_NAMES, "operator int"))
        assertEquals(listOf("CSharpDestructorDeclarationImpl(DestructorDeclaration):~Order"), names(CSharpStubIndexKeys.MEMBER_NAMES, "~Order"))
        assertEquals(listOf("CSharpIndexerDeclarationImpl(IndexerDeclaration):this"), names(CSharpStubIndexKeys.MEMBER_NAMES, "this"))
        // nothing from bodies, from the level of a namespace or the file, or under a type without a name
        for (name in listOf("local", "Local", "TopLevel", "Lost", "Field")) assertEquals(name, emptyList<String>(), names(CSharpStubIndexKeys.MEMBER_NAMES, name))
        assertEquals(listOf("CSharpMethodDeclarationImpl(MethodDeclaration):Twice"), names(CSharpStubIndexKeys.EXTENSION_METHODS, "Twice"))
        assertEquals(listOf("CSharpMethodDeclarationImpl(MethodDeclaration):RunAsync"), names(CSharpStubIndexKeys.ATTRIBUTES, "Fact"))
        assertEquals(listOf("CSharpMethodDeclarationImpl(MethodDeclaration):RunAsync"), names(CSharpStubIndexKeys.ATTRIBUTES, "Trait"))
        assertEquals(listOf("CSharpMethodDeclarationImpl(MethodDeclaration):Check"), names(CSharpStubIndexKeys.ATTRIBUTES, "Test"))
        assertEquals(listOf("CSharpRecordDeclarationImpl(RecordDeclaration):Point"), names(CSharpStubIndexKeys.ATTRIBUTES, "TestFixture"))

        val order = StubIndex.getElements(CSharpStubIndexKeys.TYPE_NAMES, "Order", project, GlobalSearchScope.allScope(project), CSharpElement::class.java).single()
        val stub = (order as CSharpStubElementImpl).stub!!
        assertEquals(listOf("public", "sealed", "partial"), stub.modifiers)
        assertEquals(2, stub.arity)
        assertEquals(listOf("Base<T>", "IOrder"), stub.baseTypes)
        assertEquals(listOf("Serializable"), stub.attributes)
        assertEquals("Shop.Orders", order.parent.let { (it as CSharpStubElementImpl).name })
        assertSame(file, order.containingFile)
        assertFalse("no AST was loaded", (file as PsiFileImpl).isContentsLoaded)
    }

    fun testSerializationRoundTrip() {
        val root = CSharpStubDefinition().builder.buildStubTree(myFixture.configureByText("Round.cs", sample))!!
        val bytes = ByteArrayOutputStream().also { SerializationManagerEx.getInstanceEx().serialize(root, it) }.toByteArray()
        val read = SerializationManagerEx.getInstanceEx().deserialize(ByteArrayInputStream(bytes))
        assertEquals(DebugUtil.stubTreeToString(root), DebugUtil.stubTreeToString(read))
    }

    /** Typing a type in, renaming it: the index follows the document. */
    fun testEditsUpdateTheIndex() {
        myFixture.configureByText("Edit.cs", "namespace N { class Before { } }\n")
        fun types(name: String) = StubIndex.getElements(CSharpStubIndexKeys.TYPE_NAMES, name, project, GlobalSearchScope.allScope(project), CSharpElement::class.java).map { (it as NavigationItem).name }
        assertEquals(listOf("Before"), types("Before"))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.insertString(document.text.indexOf("}"), " class Added { void Run() { } } ")
            document.replaceString(document.text.indexOf("Before"), document.text.indexOf("Before") + "Before".length, "After")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(emptyList<String>(), types("Before"))
        assertEquals(listOf("After"), types("After"))
        assertEquals(listOf("Added"), types("Added"))
        assertEquals(listOf("Run"), StubIndex.getElements(CSharpStubIndexKeys.MEMBER_NAMES, "Run", project, GlobalSearchScope.allScope(project), CSharpElement::class.java).map { (it as NavigationItem).name })
    }

    /** The indexer parses with the `#if` symbols of the file (on its `VirtualFile`), as the editor does: no stub / AST mismatch. */
    fun testFileSymbolsReachTheIndexer() {
        val virtualFile = myFixture.tempDirFixture.createFile("Symbols.cs", "#if DEBUG\nclass WithDebug { }\n#else\nclass WithoutDebug { }\n#endif\n")
        virtualFile.putUserData(CSharpPreprocessorSymbols.KEY, emptySet())
        val file = psiManager.findFile(virtualFile)!!
        fun types(name: String) = StubIndex.getElements(CSharpStubIndexKeys.TYPE_NAMES, name, project, GlobalSearchScope.allScope(project), CSharpElement::class.java).map { (it as NavigationItem).name }
        assertEquals(listOf("WithoutDebug"), types("WithoutDebug"))
        assertEquals(emptyList<String>(), types("WithDebug"))
        StubTextInconsistencyException.checkStubTextConsistency(file)
    }

    /** A file in the project that is indexed, with no PSI cached: its stubs come from the index. */
    private fun indexed(name: String, text: String): PsiFile {
        val virtualFile = myFixture.addFileToProject(name, text).virtualFile
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        return psiManager.findFile(virtualFile)!!
    }
}
