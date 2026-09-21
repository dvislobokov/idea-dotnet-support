package io.github.dotnetsupport

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

    fun testTheTreeHasANodePerDeclaration() {
        val file = myFixture.configureByText("OrderService.cs", source)
        assertEquals("the tree covers the text", source, file.text)
        fun outline(element: PsiElement, indent: String): List<String> = PsiTreeUtil.getChildrenOfTypeAsList(element, CSharpDeclaration::class.java)
            .flatMap { listOf("$indent${it.kind.title} ${it.name}") + outline(it, "$indent  ") }
        assertEquals(
            listOf("namespace Shop.Orders", "  class OrderService", "    field _limit", "    property Name", "    method Total", "    enum State", "      enum member New",
                "      enum member Closed", "  interface IOrderService", "    method Total"),
            outline(file, ""),
        )
        val total = PsiTreeUtil.findChildrenOfType(file, CSharpDeclaration::class.java).first { it.name == "Total" }
        assertTrue(total.text.startsWith("public decimal Total(int count)") && total.text.endsWith("}"))
        assertEquals("Total", total.nameIdentifier!!.text)
        assertEquals(source.indexOf("Total(int"), total.textOffset)
        assertEquals("Total(int count): decimal", total.presentation.presentableText)
        assertEquals("Shop.Orders.OrderService in OrderService.cs", total.presentation.locationString)

        // typing inside a body keeps the tree in step with the text
        myFixture.editor.caretModel.moveToOffset(source.indexOf("return count;"))
        myFixture.type("var doubled = count * 2;\n")
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(10, PsiTreeUtil.findChildrenOfType(myFixture.file, CSharpDeclaration::class.java).size)
        assertEquals(myFixture.editor.document.text, myFixture.file.text)
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

    fun testGotoClassAndSymbol() {
        myFixture.addFileToProject("GotoShop/OrderService.cs", source)
        myFixture.addFileToProject("GotoShop/Customer.cs", "namespace Shop;\npublic record GotoCustomer(string Name) { public string Display() => Name; }\n")
        val scope = GlobalSearchScope.projectScope(project)

        fun names(contributor: com.intellij.navigation.ChooseByNameContributorEx): Set<String> = HashSet<String>().also { contributor.processNames({ name -> it.add(name); true }, scope, null) }
        fun items(contributor: com.intellij.navigation.ChooseByNameContributorEx, name: String): List<NavigationItem> =
            ArrayList<NavigationItem>().also { contributor.processElementsWithName(name, { item -> it.add(item); true }, FindSymbolParameters.simple(project, false)) }

        val classes = CSharpGotoClassContributor()
        assertTrue(names(classes).containsAll(listOf("OrderService", "IOrderService", "State", "GotoCustomer")))
        assertFalse("members are for Go to Symbol", "Display" in names(classes))
        val found = items(classes, "GotoCustomer").single() as CSharpDeclaration
        assertEquals(DeclarationKind.RECORD, found.kind)
        assertEquals("Shop in Customer.cs", found.presentation.locationString)
        assertTrue(found.canNavigate())

        val symbols = CSharpGotoSymbolContributor()
        assertTrue(names(symbols).containsAll(listOf("GotoCustomer", "Display", "Total", "_limit")))
        // the method of the class and the one of the interface
        assertEquals(2, items(symbols, "Total").count { (it as CSharpDeclaration).containingFile.name == "OrderService.cs" })
    }

    /** The features that read the file by tokens do not notice that the tree has got deeper. */
    fun testHeuristicsOverTokensStillWork() {
        myFixture.configureByText("Program.cs", "class Program\n{\n    static void Main(string[] args)\n    {\n        System.Console.WriteLine(1);\n    }\n}\n")
        assertEquals("▶ at Main", 1, myFixture.findAllGutters().size)
        assertTrue(myFixture.doHighlighting().none { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR })
    }
}
