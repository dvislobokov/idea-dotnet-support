package io.github.dotnetsupport

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Rider's «Create …» quick fixes on a name that does not resolve (DEV_JOURNEY 4.2, 0.1.100): the rows Alt+Enter shows and what each writes —
 * a type in a new file next to the usage, in its namespace; members, locals and parameters typed from the usage.
 */
class CSharpCreateFromUsageTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [text] with `<caret>` as `Create<n>/Program.cs`; the folder of the file. */
    private fun open(text: String): String {
        val folder = "Create${files++}"
        val file = myFixture.addFileToProject("$folder/Program.cs", text.trimIndent() + "\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.doHighlighting()
        return folder
    }

    private fun creates(): List<String> = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Create ") && "'" in it && !it.startsWith("Create test") }

    private fun apply(text: String) {
        myFixture.launchAction(myFixture.findSingleIntention(text))
    }

    private fun fileText(path: String): String? = myFixture.findFileInTempDir(path)?.let { PsiManager.getInstance(project).findFile(it)?.text }

    fun testATypeOfANewExpressionGoesToANewFileInTheNamespace() {
        val folder = open("""
            namespace Shop;

            class Program
            {
                void M()
                {
                    var repo = new <caret>InMemoryRepository();
                }
            }
        """)
        assertEquals(listOf("Create class 'InMemoryRepository'", "Create record 'InMemoryRepository'", "Create struct 'InMemoryRepository'"), creates())
        apply("Create class 'InMemoryRepository'")
        assertEquals("namespace Shop;\n\npublic class InMemoryRepository\n{\n}\n", fileText("$folder/InMemoryRepository.cs"))
    }

    fun testAConstructorFromTheArgumentsAndAnEnumFromAMember() {
        val folder = open("""
            namespace Shop
            {
                class Program
                {
                    void M(string customer)
                    {
                        var order = new <caret>Order(1, customer);
                        var status = OrderStatus.Paid;
                    }
                }
            }
        """)
        apply("Create class 'Order'")
        assertEquals(
            "namespace Shop\n{\n    public class Order\n    {\n        public Order(int i, string customer)\n        {\n        }\n    }\n}\n",
            fileText("$folder/Order.cs")?.replace(Regex("""Order\(int \w+,"""), "Order(int i,"),
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("OrderStatus"))
        myFixture.doHighlighting()
        assertEquals(listOf("Create class 'OrderStatus'", "Create enum 'OrderStatus'"), creates())
        apply("Create enum 'OrderStatus'")
        assertEquals("namespace Shop\n{\n    public enum OrderStatus\n    {\n        Paid\n    }\n}\n", fileText("$folder/OrderStatus.cs"))
    }

    fun testAnInterfaceInTheBaseList() {
        val folder = open("namespace Shop;\n\npublic class Repo : <caret>IOrderRepository\n{\n}")
        assertEquals(listOf("Create interface 'IOrderRepository'", "Create class 'IOrderRepository'"), creates())
        apply("Create interface 'IOrderRepository'")
        assertEquals("namespace Shop;\n\npublic interface IOrderRepository\n{\n}\n", fileText("$folder/IOrderRepository.cs"))
    }

    fun testAFieldFromAnAssignment() {
        open("""
            class Orders
            {
                public void Add()
                {
                    <caret>_count = 5;
                }
            }
        """)
        // the likely one first, the others as the platform sorts them
        assertEquals(listOf("Create field '_count'", "Create local variable '_count'", "Create parameter '_count'", "Create property '_count'"), creates())
        apply("Create field '_count'")
        assertEquals("class Orders\n{\n    private int _count;\n\n    public void Add()\n    {\n        _count = 5;\n    }\n}\n", myFixture.editor.document.text)
    }

    fun testALocalVariableAndAParameter() {
        open("""
            class Orders
            {
                public void Add(int count)
                {
                    <caret>total = 1.5;
                    Use(limit);
                }
                void Use(object o) { }
            }
        """)
        apply("Create local variable 'total'")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("        var total = 1.5;\n"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("limit"))
        myFixture.doHighlighting()
        apply("Create parameter 'limit'")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("public void Add(int count, object limit)"))
    }

    fun testAMethodFromACall() {
        open("""
            class Orders
            {
                public void Add()
                {
                    <caret>Recalculate(1, "a");
                }
            }
        """)
        assertEquals(listOf("Create method 'Recalculate'"), creates())
        apply("Create method 'Recalculate'")
        val text = myFixture.editor.document.text
        assertTrue(text, text.startsWith("using System;\n\n"))
        assertTrue(text, Regex("""    private void Recalculate\(int \w+, string \w+\)\n    \{\n        throw new NotImplementedException\(\);\n    }""").containsMatchIn(text))
    }

    fun testAMemberOfAnotherTypeOfTheSolution() {
        myFixture.addFileToProject("CreateOther/Repo.cs", "namespace Shop;\n\npublic class Repo\n{\n    public void Save() { }\n}\n")
        open("""
            namespace Shop;

            class Program
            {
                void M(Repo repo)
                {
                    int n = repo.<caret>Count;
                }
            }
        """)
        // «Import» of the extension `Count` is there too and stays first: the two are as the platform sorts them
        assertEquals(setOf("Create property 'Count'", "Create field 'Count'"), creates().toSet())
        apply("Create property 'Count'")
        assertEquals("namespace Shop;\n\npublic class Repo\n{\n    public void Save() { }\n\n    public int Count { get; set; }\n}\n", fileText("CreateOther/Repo.cs"))
    }
}
