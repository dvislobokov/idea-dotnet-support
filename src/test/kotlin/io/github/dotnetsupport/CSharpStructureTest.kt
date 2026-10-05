package io.github.dotnetsupport

import com.intellij.openapi.components.service
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FindSymbolParameters
import io.github.dotnetsupport.lang.CSharpBreadcrumbsProvider
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.lang.CSharpFolding
import io.github.dotnetsupport.lang.CSharpGotoClassContributor
import io.github.dotnetsupport.lang.CSharpGotoSymbolContributor
import io.github.dotnetsupport.lang.CSharpStructureViewFactory
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.lang.FoldKind

class CSharpStructureTest : BasePlatformTestCase() {
    private val source = """
        using System;
        using System.Linq;

        namespace Shop.Orders;

        /// <summary>
        /// Orders of the shop.
        /// </summary>
        public class OrderService
        {
            #region State
            private readonly int _limit = 10;
            public string Name { get; set; }
            #endregion

            // totals
            // of the lines
            public decimal Total(int count)
            {
                if (count > _limit) { return 0; }
                return count;
            }

            public enum State { New, Closed }
        }

        internal interface IOrderService { decimal Total(int count); }
    """.trimIndent()

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Through the facade, so either tree answers: the heuristic one ([CSharpDeclaration] nodes) and the native one (the default since 0.1.45). */
    fun testTheTreeHasANodePerDeclaration() {
        for (native in listOf(false, true)) {
            CSharpSyntaxTrees.forceNativeTreeForTests(native)
            val tree = if (native) "native" else "heuristic"
            val file = myFixture.configureByText("OrderService${tree.replaceFirstChar(Char::uppercase)}.cs", source)
            assertEquals(tree, native, (file as io.github.dotnetsupport.lang.CSharpFile).compilationUnit != null)
            assertEquals("the tree covers the text", source, file.text)
            val model = CSharpSyntaxModel.current
            fun outline(element: PsiElement, indent: String): List<String> = model.childDeclarations(element)
                .flatMap { val info = model.declarationOf(it)!!; listOf("$indent${info.kind.title} ${info.name}") + outline(it, "$indent  ") }
            assertEquals(
                tree,
                listOf("namespace Shop.Orders", "  class OrderService", "    field _limit", "    property Name", "    method Total", "    enum State", "      enum member New",
                    "      enum member Closed", "  interface IOrderService", "    method Total"),
                outline(file, ""),
            )
            val total = model.declarationElementAt(file, source.indexOf("return count;"))!!
            assertEquals(tree, "Total", model.declarationOf(total)?.name)
            assertTrue(tree, total.text.startsWith("public decimal Total(int count)") && total.text.endsWith("}"))
            // the name through the facade: the native PSI is not a PsiNameIdentifierOwner (yet)
            assertEquals(tree, "Total", model.declarationOf(total)!!.nameRange.substring(file.text))
            if (!native) assertEquals(tree, "Total", (total as CSharpDeclaration).nameIdentifier!!.text)
            assertEquals(tree, source.indexOf("Total(int"), total.textOffset)
            // a row of Go to Symbol as in Java: the name with the parameters, the type it is in in gray, the file on the right
            assertEquals(tree, "Total(int count)", total.presentation!!.presentableText)
            assertEquals(tree, "OrderService", total.presentation!!.locationString)
            assertEquals(tree, file.name, com.intellij.ide.util.ModuleRendererFactory.findInstance(total).getModuleTextWithIcon(total)?.text)

            // typing inside a body keeps the tree in step with the text
            myFixture.editor.caretModel.moveToOffset(source.indexOf("return count;"))
            myFixture.type("var doubled = count * 2;\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(tree, 10, model.declarations(myFixture.file).all().count())
            assertEquals(tree, 10, outline(myFixture.file, "").size)
            assertEquals(tree, myFixture.editor.document.text, myFixture.file.text)
        }
    }

    fun testStructureViewAndBreadcrumbs() {
        val file = myFixture.configureByText("Structure.cs", source)
        val model = CSharpStructureViewFactory().getStructureViewBuilder(file)!!.let { (it as com.intellij.ide.structureView.TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor) }
        try {
            fun outline(element: com.intellij.ide.util.treeView.smartTree.TreeElement, indent: String): List<String> =
                element.children.flatMap { listOf(indent + it.presentation.presentableText) + outline(it, "$indent  ") }
            assertEquals(
                listOf("Shop.Orders", "  OrderService", "    _limit: int", "    Name: string", "    Total(int count): decimal", "    State", "      New", "      Closed",
                    "  IOrderService", "    Total(int count): decimal"),
                outline(model.root, ""),
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(model)
        }

        val provider = CSharpBreadcrumbsProvider()
        val inBody = file.findElementAt(source.indexOf("return count;"))!!
        val crumbs = generateSequence(inBody) { it.parent }.filter(provider::acceptElement).map(provider::getElementInfo).toList().asReversed()
        assertEquals(listOf("Shop.Orders", "OrderService", "Total()"), crumbs)
    }

    fun testFolding() {
        val regions = CSharpFolding.regions(source).map { Triple(it.kind, it.placeholder, it.range.substring(source).lines().first().trim()) }
        assertEquals(
            listOf(
                Triple(FoldKind.USINGS, "...", "System;"),
                Triple(FoldKind.DOC_COMMENT, "/// Orders of the shop....", "/// <summary>"),
                Triple(FoldKind.BODY, "{...}", "{"),
                Triple(FoldKind.REGION, "State", "#region State"),
                Triple(FoldKind.COMMENT, "// totals...", "// totals"),
                Triple(FoldKind.BODY, "{...}", "{"),
            ),
            regions,
        )
        // one-line blocks are left alone: the enum, the interface, the if
        assertTrue(CSharpFolding.regions("class A { void M() { } }").isEmpty())

        // through the editor: the descriptors are accepted by the platform
        myFixture.configureByText("Folding.cs", source)
        com.intellij.codeInsight.folding.CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val placeholders = myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
        assertTrue(placeholders.toString(), placeholders.containsAll(listOf("...", "{...}", "State")))
    }

    /** On both trees: the items are the elements of the tree of the file, read through the facade. */
    fun testGotoClassAndSymbol() {
        for (native in listOf(false, true)) {
            CSharpSyntaxTrees.forceNativeTreeForTests(native)
            val tree = if (native) "Native" else "Heuristic"
            val service = myFixture.addFileToProject("Goto$tree/OrderService.cs", source)
            myFixture.addFileToProject("Goto$tree/Customer.cs", "namespace Shop;\npublic record Goto${tree}Customer(string Name) { public string Display() => Name; }\n")
            val scope = GlobalSearchScope.projectScope(project)

            fun names(contributor: com.intellij.navigation.ChooseByNameContributorEx): Set<String> = HashSet<String>().also { contributor.processNames({ name -> it.add(name); true }, scope, null) }
            fun items(contributor: com.intellij.navigation.ChooseByNameContributorEx, name: String): List<NavigationItem> =
                ArrayList<NavigationItem>().also { contributor.processElementsWithName(name, { item -> it.add(item); true }, FindSymbolParameters.simple(project, false)) }

            val classes = CSharpGotoClassContributor()
            assertTrue(tree, names(classes).containsAll(listOf("OrderService", "IOrderService", "State", "Goto${tree}Customer")))
            assertFalse("members are for Go to Symbol", "Display" in names(classes))
            val found = items(classes, "Goto${tree}Customer").single()
            assertEquals(tree, DeclarationKind.RECORD, CSharpSyntaxModel.current.declarationOf(found as PsiElement)?.kind)
            assertEquals(tree, native, found !is CSharpDeclaration)
            assertEquals(tree, "Shop", found.presentation!!.locationString)
            assertTrue(tree, found.canNavigate())

            val symbols = CSharpGotoSymbolContributor()
            assertTrue(tree, names(symbols).containsAll(listOf("Goto${tree}Customer", "Display", "Total", "_limit")))
            // the method of the class and the one of the interface
            assertEquals(tree, 2, items(symbols, "Total").count { (it as PsiElement).containingFile == service })
        }
    }

    /** A ready server answers the same question through `workspace/symbol`: the files it has loaded are left to it, the rest is not. */
    fun testGotoLeavesTheFilesOfTheServerToIt() {
        val inside = myFixture.addFileToProject("Covered/Inside.cs", "namespace Shop;\npublic class CoveredType { }\n")
        myFixture.addFileToProject("Loose/Outside.cs", "namespace Shop;\npublic class LooseType { }\n")
        val classes = CSharpGotoClassContributor()
        fun items(name: String): List<NavigationItem> =
            ArrayList<NavigationItem>().also { classes.processElementsWithName(name, { item -> it.add(item); true }, FindSymbolParameters.simple(project, false)) }

        val status = project.service<io.github.dotnetsupport.lsp.RoslynServerStatus>()
        try {
            assertEquals("no server: both are ours", 1, items("CoveredType").size)
            status.isReady = true
            status.loadedRoots = listOf(inside.virtualFile.parent.path)
            assertEquals("the server knows this file", 0, items("CoveredType").size)
            assertEquals("a file outside of what it has loaded stays ours", 1, items("LooseType").size)
        } finally {
            // the light project is shared with the other tests of the class and between classes
            status.isReady = false
            status.loadedRoots = emptyList()
        }
    }

    /** The features that read the file by tokens do not notice that the tree has got deeper. */
    fun testHeuristicsOverTokensStillWork() {
        myFixture.configureByText("Program.cs", "class Program\n{\n    static void Main(string[] args)\n    {\n        System.Console.WriteLine(1);\n    }\n}\n")
        assertEquals("▶ at Main", 1, myFixture.findAllGutters().size)
        assertTrue(myFixture.doHighlighting().none { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR })
    }
}
