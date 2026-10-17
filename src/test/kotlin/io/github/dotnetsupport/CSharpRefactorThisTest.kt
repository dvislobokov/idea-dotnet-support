package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpRiderPopups
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Refactor This lists Introduce Field / Introduce Parameter only where they apply (0.1.170, the Jellyfin comparison: they showed at all 25
 * places, on a type header too) and Rename on every identifier of the solution, as Rider.
 */
class CSharpRefactorThisTest : BasePlatformTestCase() {
    private var files = 0

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

    private fun configure(text: String): CSharpFile = myFixture.configureByText("RefactorThis${files++}.cs", text) as CSharpFile

    /** The ids of Introduce Field / Introduce Parameter that Refactor This leaves out at `<caret>`. */
    private fun unavailable(text: String): Set<String> {
        val file = configure(text)
        return CSharpRiderPopups.unavailableRefactorings(file, null, myFixture.caretOffset)
    }

    fun testIntroduceFieldAndParameterOnlyOnAnExpressionOfABody() {
        val code = "using System.Collections.Generic;\n\npublic class Orders\n{\n    private readonly List<int> _items = new();\n\n" +
            "    public int Count(int extra)\n    {\n        return _items.Count + extra;\n    }\n}\n"
        // the header of the type, of the method, a field: neither applies
        assertEquals(setOf("IntroduceField", "IntroduceParameter"), unavailable(code.replace("class Orders", "class Ord<caret>ers")))
        assertEquals(setOf("IntroduceField", "IntroduceParameter"), unavailable(code.replace("int Count(", "int Cou<caret>nt(")))
        assertEquals(setOf("IntroduceField", "IntroduceParameter"), unavailable(code.replace("_items = new", "_ite<caret>ms = new")))
        // an expression in the body of a method: both
        assertEquals(emptySet<String>(), unavailable(code.replace("_items.Count +", "_items.Co<caret>unt +")))
    }

    fun testIntroduceParameterNotInALambdaNorFieldThere() {
        val code = "using System;\n\npublic class Orders\n{\n    public int Get() => 1;\n\n    public void Run()\n    {\n        Func<int> f = () => Ge<caret>t() + 1;\n    }\n}\n"
        assertEquals(setOf("IntroduceField", "IntroduceParameter"), unavailable(code))
    }

    fun testRenameOnEveryIdentifierOfTheSolution() {
        val code = "public class Orders\n{\n    private int _count;\n\n    public int Count(int extra)\n    {\n        var total = _count + extra;\n        return total;\n    }\n}\n"
        for (at in listOf("class Ord<caret>ers", "_cou<caret>nt;", "int Cou<caret>nt(", "int ext<caret>ra", "var tot<caret>al")) {
            val file = configure(code.replace(at.replace("<caret>", ""), at))
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, myFixture.editor)
                .add(CommonDataKeys.PSI_FILE, file).build()
            assertTrue(at, RenameHandlerRegistry.getInstance().hasAvailableHandler(context))
        }
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpRefactorThisTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
